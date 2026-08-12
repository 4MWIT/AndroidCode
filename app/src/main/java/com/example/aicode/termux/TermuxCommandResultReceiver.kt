package com.example.aicode.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.example.aicode.logging.AiCodeLog

class TermuxCommandResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            return
        }

        val executionId = intent.getIntExtra(TermuxConstants.EXTRA_EXECUTION_ID, -1)
        if (executionId == -1) {
            AiCodeLog.agentWarn("Termux result received without execution id")
            return
        }

        val bundle = intent.getBundleExtra(TermuxConstants.EXTRA_PLUGIN_RESULT_BUNDLE) ?: Bundle.EMPTY
        val result = TermuxCommandResult(
            executionId = executionId,
            stdout = bundle.getString(TermuxConstants.EXTRA_RESULT_STDOUT, "").orEmpty(),
            stderr = bundle.getString(TermuxConstants.EXTRA_RESULT_STDERR, "").orEmpty(),
            exitCode = bundle.getInt(TermuxConstants.EXTRA_RESULT_EXIT_CODE, -1),
            errCode = bundle.getInt(TermuxConstants.EXTRA_RESULT_ERR, -1),
            errMsg = bundle.getString(TermuxConstants.EXTRA_RESULT_ERRMSG, "").orEmpty(),
        )
        AiCodeLog.agent(
            "Termux result executionId=$executionId exit=${result.exitCode} err=${result.errCode} stderr=${result.stderr.take(120)}",
        )
        TermuxCommandResultStore.complete(result)
    }
}
