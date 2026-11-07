package com.rootforsree.webshell.server

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Which kind of shell the session got. PTY is the real thing; pipes are the fallback. */
enum class ShellMode {
    PTY,
    PIPES,
}

/**
 * One interactive `/system/bin/sh` on the phone, fed by bytes from the
 * browser and streaming its output back.
 *
 * PTY path (preferred, verified against the API 36 android.jar before
 * writing this): `android.system.Os` has NO `openpty`, NO `ptsname`, NO
 * `fork`, and NO `ioctl`, so a PTY cannot be built the textbook way.
 * It can still be built with what IS there:
 *
 * 1. `Os.open("/dev/ptmx", O_RDWR | O_NOCTTY)` gives a master fd.
 * 2. Opening ptmx creates one new entry under `/dev/pts`; diffing the
 *    directory before and after the open names the slave.
 * 3. `ProcessBuilder` with stdin/stdout/stderr redirected to that slave
 *    file makes the shell's stdio the PTY. The first open in the child
 *    also makes the slave the child's controlling terminal, so job
 *    control works.
 * 4. The shell starts as `sh -c 'stty cols C rows R; exec sh -i'` so
 *    the size is set before the interactive shell starts, with no typed
 *    command to echo.
 *
 * On a PTY the line discipline does the work: Tab completion, Ctrl-C
 * (0x03 becomes SIGINT for the foreground group), Ctrl-Z, and
 * full-screen apps all behave like a real terminal.
 *
 * Pipe path (fallback when ptmx cannot be opened or no slave appears):
 * plain `ProcessBuilder` pipes. Ctrl-C has no line discipline to turn
 * it into a signal, so a lone 0x03 byte is translated here into
 * SIGINT against the shell (or its process group when `setsid` gave it
 * one). Tab completion does not work on pipes: without a tty the shell
 * reads plain lines. Full-screen apps are best-effort.
 *
 * The phone is NOT rooted, so the shell runs unprivileged as the app
 * user. It can run commands and touch shared storage with permission,
 * but nothing system-level.
 */
class ShellSession private constructor(
    val mode: ShellMode,
    private val process: Process,
    private val shellInput: OutputStream,
    private val shellOutput: List<InputStream>,
    private val closeExtra: () -> Unit,
    private val sendSignal: () -> Unit,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    private var started = false

    fun start() {
        if (started) return
        started = true
        for (source in shellOutput) {
            scope.launch {
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        onOutput(buffer.copyOf(read))
                    }
                } catch (_: IOException) {
                    // Master closed (PTY) or stream ended: the session is over.
                }
            }
        }
        scope.launch {
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                // Scope cancelled: the session is being closed.
            }
            onExit()
        }
    }

    fun write(bytes: ByteArray) {
        if (mode == ShellMode.PIPES && bytes.size == 1 && bytes[0] == ETX) {
            runCatching { sendSignal() }
            return
        }
        synchronized(writeLock) {
            runCatching {
                shellInput.write(bytes)
                shellInput.flush()
            }
        }
    }

    /**
     * Terminal size. There is no ioctl in android.system.Os, so the size
     * is set from inside the shell with stty. The command echoes once
     * per resize; that is cosmetic and the size still takes effect.
     */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        write("stty cols $cols rows $rows\n".toByteArray())
    }

    fun close() {
        scope.cancel()
        runCatching { process.destroy() }
        runCatching { shellInput.close() }
        for (source in shellOutput) {
            runCatching { source.close() }
        }
        runCatching { closeExtra() }
    }

    companion object {
        private const val ETX: Byte = 0x03

        fun start(
            initialCols: Int,
            initialRows: Int,
            onOutput: (ByteArray) -> Unit,
            onExit: () -> Unit,
        ): ShellSession {
            return tryPty(initialCols, initialRows, onOutput, onExit)
                ?: startPipes(onOutput, onExit)
        }

        private fun tryPty(
            cols: Int,
            rows: Int,
            onOutput: (ByteArray) -> Unit,
            onExit: () -> Unit,
        ): ShellSession? {
            val ptsDir = File("/dev/pts")
            val before = ptsDir.list()?.toSet() ?: return null
            val masterFd = try {
                Os.open("/dev/ptmx", OsConstants.O_RDWR or OsConstants.O_NOCTTY, 0)
            } catch (_: ErrnoException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            val after = ptsDir.list()?.toSet().orEmpty()
            val created = after - before
            if (created.size != 1 || !Os.isatty(masterFd)) {
                runCatching { Os.close(masterFd) }
                return null
            }
            val slavePath = "/dev/pts/${created.single()}"
            val process = try {
                ProcessBuilder(
                    "/system/bin/sh",
                    "-c",
                    "stty cols $cols rows $rows; exec /system/bin/sh -i",
                )
                    .redirectInput(File(slavePath))
                    .redirectOutput(File(slavePath))
                    .redirectError(File(slavePath))
                    .start()
            } catch (_: IOException) {
                runCatching { Os.close(masterFd) }
                return null
            }
            if (!process.isAlive) {
                runCatching { Os.close(masterFd) }
                return null
            }
            val masterIn = FileInputStream(masterFd)
            val masterOut = FileOutputStream(masterFd)
            // The streams own the fd now: closing them closes it. Never
            // Os.close(masterFd) after this point; a reused fd number
            // could be closed by mistake.
            return ShellSession(
                mode = ShellMode.PTY,
                process = process,
                shellInput = masterOut,
                shellOutput = listOf(masterIn),
                closeExtra = {},
                sendSignal = {},
                onOutput = onOutput,
                onExit = onExit,
            )
        }

        private fun startPipes(
            onOutput: (ByteArray) -> Unit,
            onExit: () -> Unit,
        ): ShellSession {
            // setsid (toybox) gives the shell its own process group, so
            // Ctrl-C can signal the shell AND its foreground child. When
            // setsid is missing, fall back to a plain shell and signal
            // just the shell pid.
            var usesSetsid = true
            var process = try {
                ProcessBuilder("setsid", "/system/bin/sh", "-i").start()
            } catch (_: IOException) {
                usesSetsid = false
                ProcessBuilder("/system/bin/sh", "-i").start()
            }
            if (!process.isAlive) {
                process = ProcessBuilder("/system/bin/sh", "-i").start()
                usesSetsid = false
            }
            val pid = process.pid().toInt()
            val sendSignal: () -> Unit = {
                if (usesSetsid) {
                    Os.kill(-pid, OsConstants.SIGINT)
                } else {
                    Os.kill(pid, OsConstants.SIGINT)
                }
            }
            return ShellSession(
                mode = ShellMode.PIPES,
                process = process,
                shellInput = process.outputStream,
                shellOutput = listOf(process.inputStream, process.errorStream),
                closeExtra = {},
                sendSignal = sendSignal,
                onOutput = onOutput,
                onExit = onExit,
            )
        }
    }
}
