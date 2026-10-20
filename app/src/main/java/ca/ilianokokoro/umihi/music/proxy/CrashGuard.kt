package ca.ilianokokoro.umihi.music.proxy

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * On-device diagnostics for the proxy core.
 *
 * A crash inside the Go runtime cannot be caught from Java, so instead of guessing we leave
 * breadcrumbs behind: every platform-bridge callback and every native call appends a line to
 * `proxy-trace.log`, and uncaught Java exceptions are written to `proxy-crash.txt`. Whatever
 * line is last before the process dies is the one that killed it.
 *
 * The boot marker doubles as a safety valve. If a session dies shortly after starting, the next
 * launch refuses to auto-start the tunnel, so a bad node can never trap the app in a crash loop.
 */
object CrashGuard {

    private const val TAG = "UmihiProxy"
    private const val TRACE = "proxy-trace.log"
    private const val CRASH = "proxy-crash.txt"
    private const val BOOT = "proxy-boot.marker"

    private const val MAX_TRACE_BYTES = 48 * 1024
    private const val MAX_TRACE_WRITES = 4_000
    private const val MAX_REPORT_CHARS = 2_400

    /** Anything shorter is not enough to distinguish "crashed on boot" from "user swiped away". */
    const val BOOT_WINDOW_MS = 45_000L

    private val lock = Any()
    private var traceFile: File? = null
    private var writes = 0
    private var startedAt = 0L

    // ------------------------------------------------------------------ install

    fun install(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            traceFile = File(app.filesDir, TRACE)
            startedAt = System.currentTimeMillis()
            writes = 0
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = buildString {
                    append("uncaught on thread ").append(thread.name).append('\n')
                    append(error.stackTraceToString())
                }
                File(app.filesDir, CRASH).writeText(report.take(24_000))
                trace("CRASH ${error.javaClass.simpleName}: ${error.message}")
                Log.e(TAG, "uncaught exception", error)
            }
            // Hand back to the platform so Android still records the crash itself.
            previous?.uncaughtException(thread, error)
        }
    }

    // -------------------------------------------------------------------- trace

    fun trace(message: String) {
        val file = traceFile ?: return
        synchronized(lock) {
            if (writes >= MAX_TRACE_WRITES) return
            writes++
            runCatching {
                if (file.length() > MAX_TRACE_BYTES) file.writeText("")
                val offset = (System.currentTimeMillis() - startedAt) / 1000.0
                file.appendText(String.format(Locale.US, "+%6.2fs  %s\n", offset, message))
            }
        }
        Log.d(TAG, message)
    }

    // --------------------------------------------------------------- boot guard

    /**
     * Marks the start of a session.
     *
     * @return true when the previous session left its marker behind, meaning the process died
     *         before [endBoot] could run — almost always a proxy-induced crash on startup.
     */
    fun beginBoot(context: Context): Boolean {
        val marker = File(context.applicationContext.filesDir, BOOT)
        val diedEarly = marker.exists()
        runCatching { marker.writeText(System.currentTimeMillis().toString()) }
        trace(if (diedEarly) "boot: previous session ended early" else "boot: start")
        return diedEarly
    }

    /** Clears the marker once the session has proven it can survive its own startup. */
    fun endBoot(context: Context) {
        runCatching { File(context.applicationContext.filesDir, BOOT).delete() }
        trace("boot: survived ${BOOT_WINDOW_MS / 1000}s")
    }

    // ---------------------------------------------------------------- reporting

    /** Crash report plus the tail of the trace, shaped for showing inside the settings screen. */
    fun readReport(context: Context): String? {
        val dir = context.applicationContext.filesDir
        val crash = runCatching { File(dir, CRASH).takeIf { it.exists() }?.readText() }.getOrNull()
        val trace = runCatching {
            File(dir, TRACE).takeIf { it.exists() }?.readLines()?.takeLast(40)
        }.getOrNull()

        if (crash.isNullOrBlank() && trace.isNullOrEmpty()) return null

        return buildString {
            if (!crash.isNullOrBlank()) {
                append("── 崩溃堆栈 ──\n")
                append(crash.trim().take(1_200))
                append("\n\n")
            }
            if (!trace.isNullOrEmpty()) {
                append("── 崩溃前最后 40 行（t=启动后秒数）──\n")
                append(trace.joinToString("\n").take(1_200))
            }
        }.take(MAX_REPORT_CHARS)
    }

    fun clear(context: Context) {
        val dir = context.applicationContext.filesDir
        runCatching { File(dir, CRASH).delete() }
        runCatching { File(dir, TRACE).writeText("") }
    }
}
