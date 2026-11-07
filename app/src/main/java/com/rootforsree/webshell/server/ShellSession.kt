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
import java.util.concurrent.atomic.AtomicBoolean
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
 * 3. The shell runs through `setsid`, so the child is a session leader
 *    with no controlling terminal. ProcessBuilder redirects its stdio
 *    to /dev/null; the child then reopens the slave itself with
 *    `exec 0<slave 1>slave 2>slave`. The first open of a tty by a
 *    session leader with no controlling terminal acquires that tty as
 *    its controlling terminal, so the shell gets a real one.
 * 4. The shell starts as `sh -c 'exec ...; stty cols C rows R; exec sh -i'`
 *    so the size is set before the interactive shell starts, with no
 *    typed command to echo.
 *
 * The setsid step is the whole fix. The v1 mistake was redirecting
 * ProcessBuilder's stdio straight to the slave: the child was not a
 * session leader, so opening the slave acquired nothing, and the shell
 * printed "can't find tty fd", "won't have full job control", and
 * stty failed with "Inappropriate ioctl for device".
 *
 * On a PTY with a controlling terminal the line discipline does the
 * work: Tab completion, Ctrl-C (0x03 becomes SIGINT for the foreground
 * group), Ctrl-Z, and full-screen apps all behave like a real terminal.
 * `tty` reports /dev/pts/N and `stty` runs clean.
 *
 * Exit detection in PTY mode: the session ends when the master read
 * ends (the slave's last fd closed, i.e. the shell is gone). The
 * Process object is only a backup: if `setsid` forked, the object
 * tracks the intermediate parent, which exits at once while the shell
 * lives on, so a waitFor return is only trusted when the shell pid is
 * no longer a live session leader. Closing kills the whole session
 * process group, not just the Process object, for the same reason.
 * (On Android the ProcessBuilder child is not a process-group leader,
 * so setsid does not fork in practice; the fork handling is a safety
 * net, and falling back to pipes is the safe degradation if the shell
 * never appears as a session leader.)
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
    private val shellPid: Int?,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    private var started = false
    private val exitFired = AtomicBoolean(false)

    private fun fireExit() {
        if (exitFired.compareAndSet(false, true)) onExit()
    }

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
                // PTY mode only: the master read ends exactly when the
                // shell's last slave fd closes, i.e. when the shell is
                // gone. (Pipes mode keeps waitFor as the exit signal:
                // a redirected stderr could end a pipe stream early.)
                if (mode == ShellMode.PTY) fireExit()
            }
        }
        scope.launch {
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                // Scope cancelled: the session is being closed.
            }
            // The Process object can exit while the shell lives on: if
            // setsid forked, the object tracks the intermediate parent,
            // not the shell. Only report the session over when the shell
            // itself is gone as a session leader.
            val pid = shellPid ?: findFreshShellPid()
            if (pid != null && isSessionLeader(pid)) return@launch
            fireExit()
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
        // If setsid forked, destroy() only killed the intermediate
        // parent; the shell lives on as its own session leader. Kill
        // the whole session group so no orphan shell holds the PTY.
        shellPid?.let { pid ->
            runCatching { Os.kill(-pid, OsConstants.SIGTERM) }
        }
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
                    "setsid",
                    "/system/bin/sh",
                    "-c",
                    "exec 0<$slavePath 1>$slavePath 2>$slavePath; " +
                        "stty cols $cols rows $rows; exec /system/bin/sh -i",
                )
                    .redirectInput(File("/dev/null"))
                    .redirectOutput(File("/dev/null"))
                    .redirectError(File("/dev/null"))
                    .start()
            } catch (_: IOException) {
                runCatching { Os.close(masterFd) }
                return null
            }
            // The shell of a live session is a fresh session leader. If
            // the Process object is already dead and no fresh shell
            // exists, the shell died at startup: fall back to pipes.
            val shellPid = findFreshShellPid()
            if (!process.isAlive && shellPid == null) {
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
                shellPid = shellPid,
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
            // The shell's pid, for Ctrl-C. Process.pid() is NOT usable
            // here: javac sees it, but the Kotlin compiler cannot
            // resolve it (or toHandle()) against the Android stubs, so
            // the pid is found by scanning /proc for a child of this
            // process instead. Verified with kotlinc 2.0.20 + javac 17
            // against the API 36 android.jar before writing this.
            val shellPid = findShellPid()
            val sendSignal: () -> Unit = {
                val pid = shellPid
                if (pid != null) {
                    if (usesSetsid) {
                        Os.kill(-pid, OsConstants.SIGINT)
                    } else {
                        Os.kill(pid, OsConstants.SIGINT)
                    }
                }
                // No pid found: nothing to signal, and the byte is
                // dropped rather than fed to the shell as input.
            }
            return ShellSession(
                mode = ShellMode.PIPES,
                process = process,
                shellInput = process.outputStream,
                shellOutput = listOf(process.inputStream, process.errorStream),
                closeExtra = {},
                sendSignal = sendSignal,
                shellPid = shellPid,
                onOutput = onOutput,
                onExit = onExit,
            )
        }

        private fun findShellPid(): Int? {
            val parentPid = Os.getpid()
            var best: Int? = null
            val entries = File("/proc").listFiles() ?: return null
            for (entry in entries) {
                val pid = entry.name.toIntOrNull() ?: continue
                val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue
                val open = stat.indexOf('(')
                val close = stat.lastIndexOf(')')
                if (open < 0 || close <= open) continue
                val comm = stat.substring(open + 1, close)
                if (comm != "sh" && comm != "setsid") continue
                // After "(comm)" comes " state ppid ...": ppid is field 1.
                val fields = stat.substring(close + 2).split(' ')
                val ppid = fields.getOrNull(1)?.toIntOrNull() ?: continue
                if (ppid == parentPid && (best == null || pid > best)) {
                    best = pid
                }
            }
            return best
        }

        /** True when [pid] is a live session leader (its /proc stat says session == pid). */
        internal fun isSessionLeader(pid: Int): Boolean {
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return false
            return parseSessionLeader(stat, pid)
        }

        /**
         * Pure parsing of one /proc/<pid>/stat line: after "(comm)" the
         * fields are state ppid pgrp session tty_nr ... so the session
         * id is the fourth field. A session leader's session id is its
         * own pid. Kept separate from the /proc read so unit tests can
         * cover the field indexing.
         */
        internal fun parseSessionLeader(stat: String, pid: Int): Boolean {
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 0 || close <= open) return false
            val fields = stat.substring(close + 2).split(' ')
            return fields.getOrNull(3)?.toIntOrNull() == pid
        }

        /**
         * Pure parsing of one /proc/<pid>/stat line: starttime is the
         * twentieth field after "(comm)" (index 19). Kept separate from
         * the /proc read so unit tests can cover the field indexing.
         */
        internal fun parseStarttime(stat: String): Long? {
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 0 || close <= open) return null
            val fields = stat.substring(close + 2).split(' ')
            return fields.getOrNull(19)?.toLongOrNull()
        }

        /** Clock ticks since boot, from /proc/uptime (USER_HZ is 100 on Android). -1 when unreadable. */
        private fun nowTicks(): Long {
            val uptime = runCatching {
                File("/proc/uptime").readText().substringBefore(' ').toDoubleOrNull()
            }.getOrNull() ?: return -1
            return (uptime * 100).toLong()
        }

        /**
         * The pid of a session-leader `sh`/`setsid` that started within
         * the last two seconds: the shell of a session that has just
         * started. Freshness matters because other sessions' shells are
         * session leaders too; only the newest one can be this session's.
         * Retries briefly: the child calls setsid() right after
         * fork/exec, and the first scan can land before it does. Null
         * when no fresh shell appears.
         */
        internal fun findFreshShellPid(): Int? {
            val now = nowTicks()
            for (attempt in 0 until 10) {
                var bestPid: Int? = null
                var bestStart: Long = -1
                val entries = File("/proc").listFiles() ?: return null
                for (entry in entries) {
                    val pid = entry.name.toIntOrNull() ?: continue
                    val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue
                    val open = stat.indexOf('(')
                    val close = stat.lastIndexOf(')')
                    if (open < 0 || close <= open) continue
                    val comm = stat.substring(open + 1, close)
                    if (comm != "sh" && comm != "setsid") continue
                    if (!parseSessionLeader(stat, pid)) continue
                    val start = parseStarttime(stat) ?: continue
                    if (now >= 0 && now - start > FRESH_SHELL_AGE_TICKS) continue
                    if (start > bestStart || (start == bestStart && (bestPid == null || pid > bestPid))) {
                        bestPid = pid
                        bestStart = start
                    }
                }
                if (bestPid != null) return bestPid
                // Do not let an interrupt escape into the caller: a
                // missed retry just means the shell is not found.
                runCatching { Thread.sleep(10) }
            }
            return null
        }

        private const val FRESH_SHELL_AGE_TICKS = 200L // two seconds at USER_HZ 100
    }
}
