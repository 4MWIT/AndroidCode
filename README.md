# AndroidCode — 0.1 (alpha)

> **Status: alpha (0.1)** — early public release, APIs and behavior may change. Open source, MIT licensed.

On-device AI coding agent for Android — edit project folders directly on shared storage, chat with the AI agent, and optionally build/install/launch APKs directly on your phone. Formerly `Ai_code / AIcode`.

**Version: 0.1** · **License: MIT** · **Status: alpha**

## What it actually is
- **Workspace on shared storage** — pick any folder via the system picker (SAF); no copy/import, the agent works in the original directory. The only temp location is the app's private `files/projects` dir.
- **Per-project chats** — each workspace can have multiple chat threads; history is stored locally and survives restarts.
- **Pi coding agent (primary runtime)** — bundled Node.js 24.7.0 + Pi 0.84.1 running at `127.0.0.1:9877` via `pi-bridge.mjs`; streams tokens, tool calls, and file modifications back to Kotlin. Legacy Qwen/OpenCode paths remain in code but the rebuilt app supports `Pi` only.
- **Provider-agnostic models** — curated presets in `PiModelCatalog`: OpenCode Zen (`https://opencode.ai/zen/v1`) free models (DeepSeek V4 Flash, MiMo 2.5, Laguna S 2.1, Ling 3.0 Tiny, LongCat 2.0, North Mini Code, Nemotron 3 Ultra) and NVIDIA NIM (`https://integrate.api.nvidia.com/v1`) models (Thinking Machines Inkling, Z-AI GLM 5.2). Any other OpenAI-compatible endpoint works via custom `baseUrl`.
- **Autopilot loop** — `AgentAutopilotOrchestrator`: agent edit → optional `assembleDebug` via local `gradlew` → optional install via `PackageInstaller` → optional launch. Tuned by `autoBuild / autoInstall / autoLaunch` flags.
- **Local Android toolchain** — bootstrapped Termux-like runtime under `files/usr` with bundled `bash`, `dpkg`, `tar`, `node`, Java 17/21 and Android SDK discovery via `local.properties` / `ANDROID_HOME` / `BuildEnvironment.androidSdkDir`. `aapt2` is auto-resolved from `build-tools`.

## Custom endpoints
Settings → Model Settings → Base URL (`baseUrl`). Changing `providerId / modelId / baseUrl / apiType` is immediate; presets just fill these four fields. You can point it to your own proxy without touching code.

## API keys / security
- Never committed. Put your keys in `local.properties` (`aicode.opencodelKey`, `aicode.nvidiaKey`) — the file is gitignored and the template is `local.properties.example`.
- At runtime keys are encrypted with Android Keystore (`AES/GCM/NoPadding`, alias `aicode.pi.provider.key`) in `pi_secrets` prefs. Bundled `BuildConfig.OPENCODE_API_KEY / NVIDIA_API_KEY` from `local.properties` are only a fallback.
- Before publishing run `git status` and ensure `local.properties` stays untracked.

## Setup
1. Copy `local.properties.example` → `local.properties` and paste your keys.
2. `./gradlew assembleDebug` (or open in Android Studio, minSdk 27, targetSdk 28, compileSdk 36).
3. On first launch the app unpacks the Pi runtime; grant folder access when prompted.

## Tech Stack
Kotlin, Jetpack Compose + Material3, OkHttp / SSE, kotlinx-serialization/json, Android Keystore, C++ (`native-lib` via CMake), Node.js bridge (`pi-bridge.mjs`, `bridge-server.js`).

## License
MIT — see `LICENSE`
