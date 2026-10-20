package ca.ilianokokoro.umihi.music.proxy

import android.content.Context
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import java.io.File

/**
 * Owns the libbox (sing-box) runtime.
 *
 * Only one service runs at a time; switching nodes is a close + fresh start rather than a
 * reload so a half-broken config can never linger in memory.
 *
 * API notes (verified against libbox v1.14.3, the version pinned in CI):
 *  - `Libbox.newService()` / `BoxService` no longer exist since 1.13; the entry point is
 *    `CommandServer` + `startOrReloadService`.
 *  - `Libbox.setup` must run once per process before anything else.
 */
object ProxyEngine {

    private const val DEFAULT_PORT = 17890

    private val handler by lazy { ProxyServerHandler() }
    private val platform by lazy { ProxyPlatformInterface() }

    private var commandServer: CommandServer? = null
    private var setupDone = false

    /** Kernel version string, e.g. "1.14.3". */
    val kernelVersion: String
        get() = runCatching { Libbox.version() }.getOrDefault("-")

    @Synchronized
    fun ensureSetup(context: Context) {
        if (setupDone) return

        // gomobile ships libbox.so; loading twice is harmless but failing loudly here is
        // clearer than a UnsatisfiedLinkError deep inside the core.
        runCatching { System.loadLibrary("box") }
            .getOrElse { throw ProxyException("无法加载代理内核 libbox.so: ${it.message}") }

        val base = File(context.filesDir, "proxy").apply { mkdirs() }
        val working = File(base, "work").apply { mkdirs() }
        val temp = File(context.cacheDir, "proxy").apply { mkdirs() }

        val options = SetupOptions()
        options.basePath = base.absolutePath
        options.workingPath = working.absolutePath
        options.tempPath = temp.absolutePath
        options.fixAndroidStack = true
        options.debug = false

        Libbox.setup(options)
        setupDone = true
    }

    /**
     * Starts the tunnel and returns the loopback port serving the mixed inbound.
     * Throws [ProxyException] with a readable message on any core-level failure.
     */
    @Synchronized
    fun start(context: Context, node: ProxyNode): Int {
        ensureSetup(context)
        stopInternal()

        val port = pickPort()
        val config = SingBoxConfig.build(node, port)

        // Validates the JSON against the core's own schema before we spin anything up,
        // which turns config bugs into a clear message instead of an opaque core crash.
        runCatching { Libbox.checkConfig(config) }
            .onFailure { throw ProxyException("节点配置不合法: ${cleanMessage(it)}") }

        val server = Libbox.newCommandServer(handler, platform)
        try {
            server.start()
            val overrideOptions = OverrideOptions()
            overrideOptions.autoRedirect = false
            server.startOrReloadService(config, overrideOptions)
        } catch (t: Throwable) {
            runCatching { server.close() }
            throw ProxyException("启动代理失败: ${cleanMessage(t)}")
        }

        commandServer = server
        ProxyRuntime.port = port
        ProxyRuntime.lastError = null
        return port
    }

    @Synchronized
    fun stop() {
        stopInternal()
        ProxyRuntime.port = null
    }

    private fun stopInternal() {
        val current = commandServer ?: return
        commandServer = null
        ProxyRuntime.port = null
        runCatching { current.closeService() }
        runCatching { current.close() }
    }

    private fun pickPort(): Int =
        runCatching { Libbox.availablePort(DEFAULT_PORT) }
            .getOrDefault(DEFAULT_PORT)
            .takeIf { it > 0 } ?: DEFAULT_PORT

    private fun cleanMessage(t: Throwable): String {
        val raw = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        return raw.replace('\n', ' ').take(300)
    }
}

class ProxyException(message: String) : Exception(message)
