package com.example.aicode.termux

object TermuxConstants {
    const val PACKAGE_NAME = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val EXTRA_PLUGIN_RESULT_BUNDLE = "com.termux.app.terminal.io.RESULT_BUNDLE"
    const val EXTRA_RESULT_STDOUT = "stdout"
    const val EXTRA_RESULT_STDERR = "stderr"
    const val EXTRA_RESULT_EXIT_CODE = "exitCode"
    const val EXTRA_RESULT_ERR = "err"
    const val EXTRA_RESULT_ERRMSG = "errmsg"

    const val SESSION_ACTION_OPEN = "0"

    const val EXTRA_EXECUTION_ID = "com.example.aicode.termux.EXECUTION_ID"
}
