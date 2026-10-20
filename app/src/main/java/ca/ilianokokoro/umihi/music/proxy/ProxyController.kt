package ca.ilianokokoro.umihi.music.proxy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.data.repositories.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Orchestrates the proxy feature: persisted subscription + nodes, engine lifecycle, and the
 * status the Settings UI renders.
 *
 * Everything hangs off one [StateFlow] so the UI never has to guess whether the tunnel is
 * starting, up, or failed.
 */
object ProxyController {

    data class Status(
        val enabled: Boolean = false,
        val running: Boolean = false,
        val port: Int? = null,
        val nodeName: String? = null,
        val error: String? = null,
        val nodes: List<ProxyNode> = emptyList(),
        val selectedKey: String? = null,
        val subscriptionUrl: String = "",
        val busy: Boolean = false,
        val notice: String? = null,
        /** Crash/trace report surfaced after an abnormal shutdown; cleared once dismissed. */
        val diagnostics: String? = null,
        /** Node key -> what that node could reach when it was last measured. */
        val probes: Map<String, ProbeResult> = emptyMap(),
        /** Node currently being measured, so the list can show progress on that one row. */
        val probingKey: String? = null,
    )

    /**
     * Process-scoped on purpose.
     *
     * Loading the native core takes seconds and starting the service can outlive the screen that
     * triggered it. Launching this work from a `rememberCoroutineScope` meant walking out of
     * Settings cancelled the startup half-way — and, worse, reported the cancellation to the user
     * as "the coroutine scope left the composition" instead of just finishing the job.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun toggle(context: Context, enabled: Boolean) {
        scope.launch { setEnabled(context, enabled) }
    }

    fun select(context: Context, node: ProxyNode) {
        scope.launch { selectNode(context, node) }
    }

    fun importSubscription(context: Context, url: String) {
        scope.launch { refreshSubscription(context, url) }
    }

    fun clearDiagnostics() {
        _status.update { it.copy(diagnostics = null) }
    }

    private const val UA = "clash-verge/v2.0.0"

    private val KEY_ENABLED = booleanPreferencesKey("proxy-enabled")
    private val KEY_URL = stringPreferencesKey("proxy-subscription-url")
    private val KEY_NODES = stringPreferencesKey("proxy-nodes")
    private val KEY_SELECTED = stringPreferencesKey("proxy-selected-node")

    private val nodesSerializer = ListSerializer(ProxyNode.serializer())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var boundContext: Context? = null

    // ------------------------------------------------------------ lifecycle

    /** Called once from Application.onCreate; restores the tunnel if it was left enabled. */
    suspend fun bootstrap(context: Context) {
        val app = context.applicationContext
        boundContext = app

        val prefs = runCatching { app.dataStore.data.first() }.getOrNull() ?: return
        val nodes = decodeNodes(prefs[KEY_NODES])
        val selected = prefs[KEY_SELECTED]
        val url = prefs[KEY_URL] ?: ""
        val enabled = prefs[KEY_ENABLED] ?: false

        _status.update {
            it.copy(
                enabled = enabled,
                nodes = nodes,
                selectedKey = selected,
                subscriptionUrl = url,
            )
        }

        // Publish intent before starting so the first requests wait for the tunnel instead of
        // racing it (only relevant when the proxy is actually enabled).
        ProxyRuntime.enabled = enabled

        if (enabled && nodes.isNotEmpty()) {
            startTunnel(app, nodes, selected)
        } else if (enabled) {
            // Enabled with no nodes would make every request wait out the readiness timeout.
            ProxyRuntime.enabled = false
        }
    }

    /**
     * Boot path used after a session died early.
     *
     * The tunnel is forced off so the app can actually open, but the subscription and node list
     * are kept: the user only has to flip the switch back on once the cause is understood.
     */
    suspend fun recoverFromCrash(context: Context) {
        val app = context.applicationContext
        boundContext = app

        val prefs = runCatching { app.dataStore.data.first() }.getOrNull()
        val wasEnabled = prefs?.get(KEY_ENABLED) ?: false

        if (wasEnabled) {
            persist { it[KEY_ENABLED] = false }
        }
        ProxyRuntime.enabled = false

        _status.update {
            it.copy(
                enabled = false,
                running = false,
                port = null,
                nodeName = null,
                busy = false,
                nodes = decodeNodes(prefs?.get(KEY_NODES)),
                selectedKey = prefs?.get(KEY_SELECTED),
                subscriptionUrl = prefs?.get(KEY_URL) ?: "",
                error = "上次启动后进程异常结束，已自动关闭代理以免反复闪退。诊断信息见下方",
                diagnostics = CrashGuard.readReport(app),
            )
        }
        CrashGuard.trace("boot: crash recovery applied (wasEnabled=$wasEnabled)")
    }

    fun clearCrashReport(context: Context) {
        CrashGuard.clear(context)
        _status.update { it.copy(diagnostics = null, error = null) }
    }

    // --------------------------------------------------------------- toggle

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        boundContext = app
        persist { it[KEY_ENABLED] = enabled }
        _status.update { it.copy(enabled = enabled, error = null, notice = null) }
        ProxyRuntime.enabled = enabled

        if (enabled) {
            startTunnel(app, _status.value.nodes, _status.value.selectedKey)
        } else {
            withContext(Dispatchers.IO) { ProxyEngine.stop() }
            ProxyRuntime.enabled = false
            _status.update { it.copy(running = false, port = null, nodeName = null) }
        }

        // OkHttp keeps sockets it already opened and never re-consults the selector for them, so
        // a route opened before the toggle would keep being reused — the app would look
        // permanently unproxied until the process restarts.
        UmihiHttpClient.dropConnections()
    }

    // ------------------------------------------------------------ selection

    suspend fun selectNode(context: Context, node: ProxyNode) {
        val app = context.applicationContext
        boundContext = app
        persist { it[KEY_SELECTED] = node.key }
        _status.update { it.copy(selectedKey = node.key, notice = null) }

        if (_status.value.enabled) {
            startTunnel(app, _status.value.nodes, node.key)
            // The tunnel was just restarted on this node, so measuring it costs nothing extra —
            // and its verdict is the reason this screen exists.
            scope.launch { probeNode(app, node) }
        }
    }

    // ----------------------------------------------------------------- probe

    /**
     * Measures what [node] can reach and remembers the verdict for the list.
     *
     * There is only one tunnel in the process, so measuring a node that is not the active one
     * means bringing it up and then putting the previously selected node back; playback breaks
     * for the duration. Measuring the node already in use restarts nothing, which is why that
     * row answers immediately.
     *
     * A node cannot be judged by a TCP handshake alone: several exits carry plain YouTube
     * traffic while answering the music catalog with an empty shelf, and telling those apart is
     * the entire point — see [ProbeResult].
     */
    suspend fun probeNode(context: Context, node: ProxyNode) {
        val app = context.applicationContext
        boundContext = app

        if (!_status.value.enabled) {
            _status.update { it.copy(notice = "先打开代理再检测节点") }
            return
        }

        val previousKey = _status.value.selectedKey
        val alreadyActive = _status.value.running && node.key == previousKey

        _status.update { it.copy(probingKey = node.key, error = null, notice = null) }

        withContext(Dispatchers.IO) {
            try {
                if (!alreadyActive) {
                    ProxyEngine.start(app, node)
                    // The exit just changed; pooled sockets still belong to the old one.
                    UmihiHttpClient.dropConnections()
                }

                val result = ProxyProbe.measure()
                _status.update { it.copy(probes = it.probes + (node.key to result)) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _status.update { it.copy(error = friendly(t)) }
            } finally {
                if (!alreadyActive) {
                    restoreTunnel(app, previousKey, node.key)
                }
                _status.update { it.copy(probingKey = null) }
            }
        }
    }

    /** Re-measures whichever node is selected, on demand. */
    fun reprobeSelected(context: Context) {
        val selected = _status.value.nodes.firstOrNull { it.key == _status.value.selectedKey }
            ?: return
        scope.launch { probeNode(context, selected) }
    }

    /** Puts the tunnel back on [key] after a probe, unless it never left it. */
    private suspend fun restoreTunnel(app: Context, key: String?, probedKey: String) {
        if (key == null || key == probedKey) return
        val back = _status.value.nodes.firstOrNull { it.key == key } ?: return
        runCatching { ProxyEngine.start(app, back) }
        UmihiHttpClient.dropConnections()
    }

    // --------------------------------------------------------- subscription

    suspend fun refreshSubscription(context: Context, url: String): Result<Int> {
        val app = context.applicationContext
        boundContext = app
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return Result.failure(ProxyException("请先填写订阅链接"))

        _status.update { it.copy(busy = true, error = null, notice = null, diagnostics = null) }
        return withContext(Dispatchers.IO) {
            try {
                val body = fetchSubscription(trimmed)
                val nodes = SubscriptionParser.parse(body)
                if (nodes.isEmpty()) {
                    throw ProxyException("订阅解析不到节点：支持 Clash YAML、节点链接列表或其 base64 编码")
                }

                persist {
                    it[KEY_URL] = trimmed
                    it[KEY_NODES] = json.encodeToString(nodesSerializer, nodes)
                }

                // Keep the current selection if it still exists after a refresh.
                val currentKey = _status.value.selectedKey
                val selection = nodes.firstOrNull { it.key == currentKey }
                    ?: nodes.firstOrNull { it.isDefault }
                    ?: nodes.first()

                persist { it[KEY_SELECTED] = selection.key }

                _status.update {
                    it.copy(
                        busy = false,
                        nodes = nodes,
                        selectedKey = selection.key,
                        subscriptionUrl = trimmed,
                        notice = "已导入 ${nodes.size} 个节点",
                    )
                }

                if (_status.value.enabled) {
                    startTunnel(app, nodes, selection.key)
                }

                Result.success(nodes.size)
            } catch (c: CancellationException) {
                // Cancellation is not a failure; surfacing it as an error is what confused the
                // user with "the coroutine scope left the composition".
                _status.update { it.copy(busy = false) }
                throw c
            } catch (t: Throwable) {
                _status.update { it.copy(busy = false, error = friendly(t)) }
                Result.failure(t)
            }
        }
    }

    fun clearNotice() {
        _status.update { it.copy(notice = null) }
    }

    // ---------------------------------------------------------------- engine

    private suspend fun startTunnel(
        context: Context,
        nodes: List<ProxyNode>,
        selectedKey: String?,
    ) {
        val node = nodes.firstOrNull { it.key == selectedKey } ?: nodes.firstOrNull()
        if (node == null) {
            ProxyRuntime.enabled = false
            _status.update { it.copy(running = false, error = "还没有可用节点，请先导入订阅") }
            return
        }

        _status.update { it.copy(busy = true, error = null) }
        CrashGuard.trace("tunnel: starting ${node.type} ${node.server}:${node.port}")

        withContext(Dispatchers.IO) {
            try {
                val port = ProxyEngine.start(context, node)
                ProxyRuntime.enabled = true
                _status.update {
                    it.copy(
                        busy = false,
                        running = true,
                        port = port,
                        nodeName = node.displayName,
                        error = null,
                    )
                }
                CrashGuard.trace("tunnel: up on 127.0.0.1:$port")
                // Sockets opened before this node came up belong to the previous route.
                UmihiHttpClient.dropConnections()
            } catch (c: CancellationException) {
                _status.update { it.copy(busy = false) }
                throw c
            } catch (t: Throwable) {
                val message = friendly(t)
                ProxyRuntime.lastError = message
                // Drop intent on failure: otherwise every request would block for the readiness
                // timeout before falling back, which feels far worse than failing fast.
                ProxyRuntime.enabled = false
                _status.update {
                    it.copy(
                        busy = false,
                        running = false,
                        port = null,
                        nodeName = null,
                        error = message,
                    )
                }
                CrashGuard.trace("tunnel: failed $message")
            }
        }
    }

    // ----------------------------------------------------------------- fetch

    /**
     * Subscriptions are usually reachable directly in China, but a blocked provider host is
     * common enough that a second attempt through the tunnel is worth it.
     */
    private fun fetchSubscription(url: String): String {
        val directFailure = runCatching {
            executeFetch(UmihiHttpClient.directClient, url)
        }

        directFailure.getOrNull()?.let { return it }

        if (ProxyRuntime.enabled) {
            runCatching { executeFetch(UmihiHttpClient.client, url) }
                .getOrNull()
                ?.let { return it }
        }

        throw directFailure.exceptionOrNull()
            ?: ProxyException("无法下载订阅，请检查链接或网络")
    }

    private fun executeFetch(client: okhttp3.OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ProxyException("订阅请求失败：HTTP ${response.code}")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw ProxyException("订阅内容为空")
        }
    }

    // --------------------------------------------------------------- helpers

    private suspend fun persist(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val context = boundContext ?: return
        context.dataStore.edit { block(it) }
    }

    private fun decodeNodes(raw: String?): List<ProxyNode> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(nodesSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun friendly(t: Throwable): String {
        val message = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        return message.replace('\n', ' ').take(300)
    }
}
