package ca.ilianokokoro.umihi.music.proxy

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Parses a Clash / Clash.Meta style YAML subscription into [ProxyNode]s.
 *
 * Only the `proxies:` list is consumed — proxy-groups and rules are ignored because
 * Umihi connects to a single chosen node from inside the app rather than creating a
 * device-wide tunnel.
 */
object ClashYamlParser {

    private val proxiesBlock = Regex("(?m)^proxies\\s*:")

    fun looksLikeClash(raw: String): Boolean = proxiesBlock.containsMatchIn(raw)

    @Suppress("UNCHECKED_CAST")
    fun parse(raw: String): List<ProxyNode> {
        val root = runCatching {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(raw)
        }.getOrNull() as? Map<*, *> ?: return emptyList()

        val proxies = root["proxies"] as? List<*> ?: return emptyList()

        return proxies.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            runCatching { toNode(map) }.getOrNull()
        }.filter { it.isValid() }
    }

    private fun toNode(map: Map<*, *>): ProxyNode? {
        val type = ProxyProtocols.normalize(str(map, "type") ?: return null) ?: return null
        val server = str(map, "server") ?: return null
        val port = int(map, "port") ?: return null
        if (server.isBlank() || port <= 0) return null

        val name = str(map, "name") ?: ""
        val tlsFlag = bool(map, "tls") ?: false
        val sni = str(map, "servername", "sni")
        val insecure = bool(map, "skip-cert-verify") ?: false
        val network = str(map, "network")?.lowercase()
        val fingerprint = str(map, "client-fingerprint")

        val wsOpts = map["ws-opts"] as? Map<*, *>
        val wsHeaders = wsOpts?.get("headers") as? Map<*, *>
        val grpcOpts = map["grpc-opts"] as? Map<*, *>
        val realityOpts = map["reality-opts"] as? Map<*, *>
        val pluginOpts = map["plugin-opts"] as? Map<*, *>

        return ProxyNode(
            name = name,
            type = type,
            server = server,
            port = port,
            password = str(map, "password", "auth"),
            uuid = str(map, "uuid"),
            method = when (type) {
                "ss" -> str(map, "cipher")?.let(ProxyProtocols::ssMethod)
                "vmess" -> str(map, "cipher", "security") ?: "auto"
                else -> null
            },
            alterId = int(map, "alterId", "alter-id") ?: 0,
            flow = str(map, "flow")?.takeIf { it.isNotBlank() },
            tls = tlsFlag || realityOpts != null,
            sni = sni,
            insecure = insecure,
            fingerprint = fingerprint,
            realityPublicKey = realityOpts?.let { str(it, "public-key") },
            realityShortId = realityOpts?.let { str(it, "short-id") },
            network = network,
            wsPath = wsOpts?.let { str(it, "path") },
            wsHost = wsHeaders?.let { str(it, "Host", "host") },
            grpcServiceName = grpcOpts?.let { str(it, "grpc-service-name") },
            obfs = str(map, "obfs"),
            obfsPassword = str(map, "obfs-password"),
            plugin = str(map, "plugin"),
            pluginOpts = flattenPluginOpts(pluginOpts),
            username = str(map, "username", "user"),
            congestionControl = str(map, "congestion-controller", "congestion_control"),
            upMbps = int(map, "up"),
            downMbps = int(map, "down"),
        )
    }

    /** Clash keeps ss plugin options as a nested map; sing-box wants one query-like string. */
    private fun flattenPluginOpts(opts: Map<*, *>?): String? {
        if (opts == null || opts.isEmpty()) return null
        return opts.entries
            .filter { it.value != null }
            .joinToString(";") { (k, v) ->
                when (v) {
                    is Boolean -> if (v) "$k" else "$k=false"
                    else -> "$k=$v"
                }
            }
    }

    private fun str(map: Map<*, *>, vararg keys: String): String? {
        for (k in keys) {
            val v = map[k] ?: continue
            val s = v.toString().trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return null
    }

    private fun int(map: Map<*, *>, vararg keys: String): Int? {
        for (k in keys) {
            val v = map[k] ?: continue
            when (v) {
                is Number -> return v.toInt()
                is String -> v.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun bool(map: Map<*, *>, vararg keys: String): Boolean? {
        for (k in keys) {
            val v = map[k] ?: continue
            when (v) {
                is Boolean -> return v
                is String -> when (v.trim().lowercase()) {
                    "true", "1", "yes" -> return true
                    "false", "0", "no" -> return false
                }
            }
        }
        return null
    }
}
