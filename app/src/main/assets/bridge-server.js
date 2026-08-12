/**
 * Qwen Code ACP Bridge Server
 *
 * Runs inside the Android app via nodejs-mobile (JNI).
 * Provides HTTP + SSE interface to communicate with qwen --acp.
 *
 * Endpoints:
 *   POST /initialize  - Initialize ACP session
 *   POST /prompt      - Send a prompt to qwen
 *   GET  /stream      - SSE stream for tokens/processing/errors
 *   POST /cancel      - Kill current qwen process
 *   GET  /health      - Health check
 *   POST /permission  - Respond to permission request
 */

var http = require("http");
var spawn = require("child_process").spawn;
var path = require("path");
var fs = require("fs");

// Configuration
var PORT = process.env.QWEN_BRIDGE_PORT || 9876;
var HOST = "127.0.0.1";

// Paths from environment
var QWEN_CLI_PATH =
  process.env.QWEN_CLI_PATH ||
  path.join(
    process.env.HOME || "/data/data/com.tom.rv2ide/files",
    "nodejs/lib/node_modules/@qwen-code/qwen-code/cli.js",
  );
var PROJECT_ROOT = process.env.QWEN_PROJECT_ROOT || process.cwd();
var QWEN_HOME = process.env.HOME || "/data/data/com.tom.rv2ide/files/home";
var QWEN_NODE_DIR =
  process.env.QWEN_NODE_DIR ||
  path.join(
    process.env.HOME || "/data/data/com.tom.rv2ide/files",
    "nodejs/lib",
  );
var BRIDGE_LOG_FILE =
  process.env.QWEN_BRIDGE_LOG_FILE ||
  path.join(QWEN_HOME || "/data/data/com.tom.rv2ide/files/home", "bridge-debug.log");

// State
var qwenProcess = null;
var authProcess = null;
var isRunning = false;
var currentRequestId = 0;
var pendingRequests = {};
var sseClients = [];
var initialized = false;
var stdoutLineBuffer = "";
var latestAuthState = {
  authenticated: false,
  authType: null,
  message: "Qwen OAuth is not configured yet",
};

function logBridge(message) {
  var line = "[bridge] " + new Date().toISOString() + " " + message;
  try {
    console.log(line);
  } catch (e) {}
  try {
    fs.appendFileSync(BRIDGE_LOG_FILE, line + "\n");
  } catch (e) {}
}

function logBridgeError(prefix, error) {
  var message = prefix;
  if (error) {
    if (error.stack) {
      message += ": " + error.stack;
    } else if (error.message) {
      message += ": " + error.message;
    } else {
      message += ": " + String(error);
    }
  }
  logBridge(message);
}

function isRpcError(response) {
  return !!(response && response.error);
}

// Health endpoint
function getHealth() {
  return JSON.stringify({
    status: "ok",
    qwenRunning: !!qwenProcess && isRunning,
    authRunning: !!authProcess,
    initialized: initialized,
    sseClients: sseClients.length,
    pid: qwenProcess ? qwenProcess.pid : null,
  });
}

// Broadcast to all SSE clients
function broadcast(eventType, data) {
  var msg = JSON.stringify({
    type: eventType,
    data: data,
    timestamp: Date.now(),
  });
  var line = "data: " + msg + "\n\n";
  var keptClients = [];
  for (var i = 0; i < sseClients.length; i++) {
    try {
      sseClients[i].write(line);
      keptClients.push(sseClients[i]);
    } catch (e) {
      // Client disconnected
    }
  }
  sseClients = keptClients;
}

function createQwenEnv() {
  return Object.assign({}, process.env, {
    HOME: QWEN_HOME,
    NODE_PATH: path.join(QWEN_NODE_DIR, "node_modules"),
  });
}

function parseAuthInfo(text) {
  var normalized = text || "";
  var lower = normalized.toLowerCase();
  var urlMatch = normalized.match(/https?:\/\/\S+/);
  var codeMatch = normalized.match(/[A-Z0-9]{4}-[A-Z0-9]{4}/);
  var authenticated =
    (lower.indexOf("authenticated") !== -1 ||
      lower.indexOf("logged in") !== -1 ||
      lower.indexOf("qwen oauth") !== -1) &&
    lower.indexOf("not authenticated") === -1 &&
    lower.indexOf("not configured") === -1;
  var authType = null;

  if (
    lower.indexOf("qwen oauth") !== -1 ||
    lower.indexOf("qwen-oauth") !== -1
  ) {
    authType = "qwen-oauth";
  } else if (lower.indexOf("coding plan") !== -1) {
    authType = "coding-plan";
  } else if (lower.indexOf("api key") !== -1) {
    authType = "api-key";
  }

  return {
    authenticated: authenticated,
    authType: authType,
    url: urlMatch ? urlMatch[0] : null,
    userCode: codeMatch ? codeMatch[0] : null,
  };
}

function updateLatestAuthState(result) {
  latestAuthState = {
    authenticated: !!result.authenticated,
    authType: result.authType || null,
    message:
      result.message ||
      (result.authenticated
        ? "Qwen OAuth is ready"
        : "Qwen OAuth is not configured yet"),
  };
}

function collectCommandOutput(args) {
  return new Promise(function (resolve, reject) {
    logBridge("collectCommandOutput " + JSON.stringify(args));
    var child = spawn(process.execPath, [QWEN_CLI_PATH].concat(args), {
      stdio: ["ignore", "pipe", "pipe"],
      cwd: PROJECT_ROOT,
      env: createQwenEnv(),
    });
    var stdout = "";
    var stderr = "";

    child.stdout.on("data", function (chunk) {
      stdout += chunk.toString();
    });
    child.stderr.on("data", function (chunk) {
      stderr += chunk.toString();
    });
    child.on("error", function (error) {
      logBridgeError("collectCommandOutput error for " + JSON.stringify(args), error);
      reject(error);
    });
    child.on("close", function (code) {
      logBridge("collectCommandOutput closed code=" + code + " args=" + JSON.stringify(args));
      resolve({
        exitCode: code,
        stdout: stdout,
        stderr: stderr,
      });
    });
  });
}

function fetchAuthStatus() {
  return collectCommandOutput(["auth", "status"]).then(function (result) {
    var merged = ((result.stdout || "") + "\n" + (result.stderr || "")).trim();
    var parsed = parseAuthInfo(merged);
    var status = {
      authenticated: parsed.authenticated,
      authType: parsed.authType,
      message:
        merged ||
        (parsed.authenticated
          ? "Qwen auth status looks ready"
          : "Qwen auth status is empty"),
    };
    updateLatestAuthState(status);
    return status;
  });
}

function startQwenOAuth() {
  if (authProcess) {
    return Promise.resolve({
      started: true,
      alreadyRunning: true,
    });
  }

  return new Promise(function (resolve, reject) {
    logBridge("startQwenOAuth invoked");
    var stdoutBuffer = "";
    var stderrBuffer = "";
    var combinedOutput = "";
    var resolved = false;

    function handleLine(line) {
      var cleanLine = line.trim();
      if (!cleanLine) {
        return;
      }

      combinedOutput += cleanLine + "\n";
      broadcast("auth_log", cleanLine);

      var parsed = parseAuthInfo(cleanLine);
      if (parsed.url) {
        broadcast("auth_url", {
          url: parsed.url,
          userCode: parsed.userCode,
        });
        if (!resolved) {
          resolved = true;
          resolve({
            started: true,
            url: parsed.url,
            userCode: parsed.userCode,
          });
        }
      }
    }

    authProcess = spawn(process.execPath, [QWEN_CLI_PATH, "auth", "qwen-oauth"], {
      stdio: ["ignore", "pipe", "pipe"],
      cwd: PROJECT_ROOT,
      env: createQwenEnv(),
    });

    authProcess.stdout.on("data", function (chunk) {
      stdoutBuffer += chunk.toString();
      var lines = stdoutBuffer.split("\n");
      stdoutBuffer = lines.pop() || "";
      for (var i = 0; i < lines.length; i++) {
        handleLine(lines[i]);
      }
    });

    authProcess.stderr.on("data", function (chunk) {
      stderrBuffer += chunk.toString();
      var lines = stderrBuffer.split("\n");
      stderrBuffer = lines.pop() || "";
      for (var i = 0; i < lines.length; i++) {
        handleLine(lines[i]);
      }
    });

    authProcess.on("error", function (error) {
      logBridgeError("authProcess error", error);
      authProcess = null;
      updateLatestAuthState({
        authenticated: false,
        authType: null,
        message: error.message,
      });
      broadcast("auth_complete", {
        success: false,
        authenticated: false,
        authType: null,
        message: error.message,
      });
      if (!resolved) {
        resolved = true;
        reject(error);
      }
    });

    authProcess.on("close", function (code) {
      logBridge("authProcess closed code=" + code);
      authProcess = null;
      fetchAuthStatus()
        .then(function (status) {
          var success = code === 0 && status.authenticated;
          broadcast("auth_complete", {
            success: success,
            authenticated: status.authenticated,
            authType: status.authType,
            message:
              status.message ||
              combinedOutput.trim() ||
              ("qwen auth finished with code " + code),
          });
          if (!resolved) {
            resolved = true;
            resolve({
              started: code === 0,
              authenticated: status.authenticated,
            });
          }
        })
        .catch(function (statusError) {
          var message =
            (statusError && statusError.message) ||
            combinedOutput.trim() ||
            "qwen auth status failed";
          updateLatestAuthState({
            authenticated: false,
            authType: null,
            message: message,
          });
          broadcast("auth_complete", {
            success: false,
            authenticated: false,
            authType: null,
            message: message,
          });
          if (!resolved) {
            resolved = true;
            resolve({
              started: code === 0,
              authenticated: false,
            });
          }
        });
    });
  });
}

function cancelAuth() {
  if (!authProcess) {
    return false;
  }
  authProcess.kill("SIGTERM");
  authProcess = null;
  return true;
}

// Start qwen --acp process
function startQwen() {
  if (qwenProcess && isRunning) {
    broadcast("info", "qwen already running");
    return;
  }

  isRunning = true;
  currentRequestId = 0;
  stdoutLineBuffer = "";

  logBridge("startQwen with cli=" + QWEN_CLI_PATH + " project=" + PROJECT_ROOT);
  broadcast("info", "Starting qwen process...");

  var env = createQwenEnv();

  qwenProcess = spawn(process.execPath, [QWEN_CLI_PATH, "--acp"], {
    stdio: ["pipe", "pipe", "pipe"],
    cwd: PROJECT_ROOT,
    env: env,
  });

  qwenProcess.stderr.on("data", function (chunk) {
    var text = chunk.toString();
    logBridge("qwen stderr: " + text.trim());
    broadcast("stderr", text.trim());
  });

  qwenProcess.on("error", function (err) {
    logBridgeError("qwen spawn error", err);
    broadcast("qwen_error", err.message);
  });

  qwenProcess.stdout.on("data", function (chunk) {
    var text = chunk.toString();
    stdoutLineBuffer += text;

    var lines = stdoutLineBuffer.split("\n");
    stdoutLineBuffer = lines.pop() || "";

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (!line.trim()) continue;

      try {
        var msg = JSON.parse(line);
        handleQwenMessage(msg);
      } catch (e) {
        broadcast("raw_output", line);
      }
    }
  });

  qwenProcess.stderr.on("data", function (chunk) {
    broadcast("stderr", chunk.toString().trim());
  });

  qwenProcess.on("close", function (code) {
    logBridge("qwen process closed code=" + code);
    isRunning = false;
    broadcast("qwen_exit", { code: code });
    qwenProcess = null;

    // Fail all pending requests
    for (var id in pendingRequests) {
      var resolve = pendingRequests[id];
      resolve({
        jsonrpc: "2.0",
        id: parseInt(id),
        error: { code: -32603, message: "qwen process exited" },
      });
    }
    pendingRequests = {};
  });

  qwenProcess.on("error", function (err) {
    logBridgeError("qwen process fatal error", err);
    broadcast("qwen_error", err.message);
    isRunning = false;
    qwenProcess = null;
  });
}

// Handle messages from qwen stdout
function handleQwenMessage(msg) {
  var method = msg.method;
  var id = msg.id;
  var params = msg.params;

  if (method) {
    switch (method) {
      case "notify/processing":
        broadcast(
          "processing",
          params && params.message ? params.message : "Processing...",
        );
        break;
      case "notify/token":
        broadcast("token", params && params.token ? params.token : "");
        break;
      case "notify/complete":
        broadcast("complete", params || { response: "Done", modifications: [] });
        initialized = false;
        break;
      case "notify/error":
        broadcast(
          "error",
          params && params.message ? params.message : "Unknown error",
        );
        break;
      case "notify/fileModify":
        broadcast("file_modify", params);
        break;
      case "notify/fileModified":
        broadcast("file_modified", params);
        break;
      case "request/permission":
        broadcast("permission_request", params);
        break;
      default:
        broadcast("unknown_method", { method: method, params: params });
    }
  } else if (id !== undefined) {
    if (pendingRequests[id]) {
      var resolve = pendingRequests[id];
      delete pendingRequests[id];
      resolve(msg);
    }
  }
}

// Send a message to qwen stdin
function sendToQwen(msg) {
  if (!qwenProcess || !isRunning) {
    return Promise.reject(new Error("qwen not running"));
  }
  return new Promise(function (resolve, reject) {
    var id = ++currentRequestId;
    msg.id = id;
    msg.jsonrpc = "2.0";
    var jsonMsg = JSON.stringify(msg);

    var timeout = setTimeout(function () {
      delete pendingRequests[id];
      reject(new Error("Request " + id + " timed out"));
    }, 120000);

    pendingRequests[id] = function (response) {
      clearTimeout(timeout);
      resolve(response);
    };

    try {
      qwenProcess.stdin.write(jsonMsg + "\n");
    } catch (e) {
      clearTimeout(timeout);
      delete pendingRequests[id];
      reject(e);
    }
  });
}

// Kill qwen process
function killQwen() {
  if (qwenProcess) {
    qwenProcess.kill("SIGTERM");
    broadcast("info", "qwen process killed");
  } else {
    broadcast("info", "qwen not running");
  }
}

// HTTP Server
var server = http.createServer(function (req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(getHealth());
  } else if (req.method === "GET" && req.url === "/stream") {
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    res.write(": connected\n\n");
    sseClients.push(res);

    req.on("close", function () {
      var idx = sseClients.indexOf(res);
      if (idx !== -1) sseClients.splice(idx, 1);
    });
  } else if (req.method === "POST" && req.url === "/initialize") {
    if (!qwenProcess || !isRunning) {
      startQwen();
    }

    var body = "";
    req.on("data", function (c) {
      body += c;
    });
    req.on("end", function () {
      try {
        var config = JSON.parse(body || "{}");
        if (config.projectRoot) {
          PROJECT_ROOT = config.projectRoot;
        }
        sendToQwen({
          method: "initialize",
          params: {
            projectRoot: config.projectRoot || PROJECT_ROOT,
            model: config.model || null,
            capabilities: config.capabilities || [
              "fileRead",
              "fileWrite",
              "bash",
              "webSearch",
            ],
          },
        })
          .then(function (response) {
            if (isRpcError(response)) {
              res.writeHead(500, { "Content-Type": "application/json" });
              res.end(JSON.stringify({ error: response.error.message || "initialize failed" }));
              return;
            }

            initialized = true;
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ status: "ok", initialized: true, response: response.result || null }));
          })
          .catch(function (e) {
            res.writeHead(500, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: e.message }));
          });
      } catch (e) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
  } else if (req.method === "POST" && req.url === "/prompt") {
    if (!qwenProcess || !isRunning) {
      startQwen();
      setTimeout(function () {
        handlePromptBody(req, res);
      }, 1000);
    } else {
      handlePromptBody(req, res);
    }
  } else if (req.method === "POST" && req.url === "/cancel") {
    killQwen();
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "killed" }));
  } else if (req.method === "POST" && req.url === "/permission") {
    var body = "";
    req.on("data", function (c) {
      body += c;
    });
    req.on("end", function () {
      try {
        var config = JSON.parse(body || "{}");
        sendToQwen({
          method: "respondPermission",
          params: { id: config.id, granted: config.granted || false },
        })
          .then(function () {
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ status: "ok" }));
          })
          .catch(function (e) {
            res.writeHead(500, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: e.message }));
          });
      } catch (e) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
  } else if (req.method === "GET" && req.url === "/auth/status") {
    fetchAuthStatus()
      .then(function (status) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(status));
      })
      .catch(function (e) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            authenticated: false,
            authType: null,
            message: e.message,
          }),
        );
      });
  } else if (req.method === "POST" && req.url === "/auth/qwen-oauth/start") {
    startQwenOAuth()
      .then(function (result) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(result));
      })
      .catch(function (e) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: e.message }));
      });
  } else if (req.method === "POST" && req.url === "/auth/cancel") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ cancelled: cancelAuth() }));
  } else {
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not found" }));
  }
});

server.on("error", function (error) {
  logBridgeError("http server error", error);
});

function handlePromptBody(req, res) {
  var body = "";
  req.on("data", function (c) {
    body += c;
  });
  req.on("end", function () {
    try {
      var config = JSON.parse(body || "{}");
      sendToQwen({
        method: "sendPrompt",
        params: {
          prompt: config.prompt || "",
          context: config.context || null,
          writeEnabled: config.writeEnabled !== false,
        },
      })
        .then(function (response) {
          if (isRpcError(response)) {
            res.writeHead(500, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: response.error.message || "sendPrompt failed" }));
            return;
          }
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify(response));
        })
        .catch(function (e) {
          res.writeHead(500, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: e.message }));
        });
    } catch (e) {
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: e.message }));
    }
  });
}

// Start server
server.listen(PORT, HOST, function () {
  logBridge("Qwen ACP Bridge running on http://" + HOST + ":" + PORT);
  logBridge("Qwen CLI: " + QWEN_CLI_PATH);
  logBridge("Project: " + PROJECT_ROOT);
  logBridge("Bridge log file: " + BRIDGE_LOG_FILE);
});

// Graceful shutdown
process.on("SIGTERM", function () {
  killQwen();
  cancelAuth();
  server.close(function () {
    process.exit(0);
  });
});

process.on("SIGINT", function () {
  killQwen();
  cancelAuth();
  server.close(function () {
    process.exit(0);
  });
});

process.on("uncaughtException", function (error) {
  logBridgeError("uncaughtException", error);
});

process.on("unhandledRejection", function (error) {
  logBridgeError("unhandledRejection", error);
});
