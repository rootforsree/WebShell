package com.rootforsree.webshell.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the [WebShellServer] for the process and exposes its state as
 * flows for the UI. One owner, so the Activity, the ViewModel, and the
 * foreground service can never start or stop different servers.
 *
 * Lifecycle: [start] brings the server up and starts the foreground
 * service that keeps the process alive; [stop] tears both down. The
 * token is fresh on every start, and the log keeps the last 100 lines.
 */
class ServerHost(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val server = WebShellServer(context, ::appendLog)

    val isRunning: StateFlow<Boolean> field = MutableStateFlow(false)

    /** Null while stopped. */
    val sessionInfo: StateFlow<ServerInfo?> field = MutableStateFlow(null)

    val logLines: StateFlow<List<String>> field = MutableStateFlow(emptyList())

    /** Last start failure, if any. Cleared on the next start. */
    val error: StateFlow<String?> field = MutableStateFlow(null)

    fun start() {
        if (isRunning.value) return
        scope.launch {
            error.value = null
            try {
                val info = withContext(Dispatchers.IO) { server.start() }
                sessionInfo.value = info
                isRunning.value = true
                startForegroundService()
                appendLog("Open one of the links below in any browser.")
            } catch (e: Exception) {
                error.value = "Could not start the server: ${e.message ?: e.javaClass.simpleName}"
                appendLog("Start failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun stop() {
        if (!isRunning.value) return
        scope.launch {
            withContext(Dispatchers.IO) { server.stop() }
            sessionInfo.value = null
            isRunning.value = false
            stopForegroundService()
        }
    }

    fun clearError() {
        error.value = null
    }

    /** True when the shell can already touch all of /sdcard. */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Opens the system screen that lets the user exempt the app from
     * battery optimization, so Android does not stop the server while
     * it runs in the background. In context, with an explanation in
     * the UI next to the button, never at launch.
     */
    fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Opens the system all-files-access screen for this app. */
    fun requestAllFilesAccess() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun startForegroundService() {
        val intent = Intent(context, WebShellService::class.java).apply {
            action = WebShellService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopForegroundService() {
        val intent = Intent(context, WebShellService::class.java).apply {
            action = WebShellService.ACTION_STOP
        }
        context.startService(intent)
    }

    private fun appendLog(line: String) {
        val updated = (logLines.value + line).takeLast(MAX_LOG_LINES)
        logLines.value = updated
    }

    companion object {
        private const val MAX_LOG_LINES = 100
    }
}
