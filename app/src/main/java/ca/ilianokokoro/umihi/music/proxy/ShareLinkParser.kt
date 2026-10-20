package ca.ilianokokoro.umihi.music.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder

/**
 * Parses single-node share links (vmess://, vless://, trojan://, ss://, hysteria2://,
 * tuic://, socks://, http://) into [ProxyNode]s.
 *
 * Implemented with plain string handling on purpose: no android.net.Uri dependency so the
 * whole parser can be unit tested on a normal JVM.
 */
object ShareLinkParser {

    private val linkScheme = Regex("""(?m)^[a-z0-9]+://""", RegexOption.IGNORE_CASE)

    fun looksLikeLinks(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!linkScheme.containsMatchIn(trimmed)) return false
        return trimmed.lineSequence().any { line ->
            val l = line.trim()
            l.contains("://") && ProxyProtocols.normalize(l.substringBefore("://")) != null
        }
    }

    fun parseAll(raw: String): List<ProxyNode> {
        val out = mutableListOf<ProxyNode>()
        for (line in raw.lineSequence()) {
            val link = line.trim()
            if (link.isEmpty() || !link.contains("://")) continue
            val node = runCatching { parse(link) }.getOrNull() ?: continue
            if (node.isValid()) out += node
        }
        return out
    }

    fun parse(link: String): ProxyNode? {
        val scheme = link.substringBefore("://").lowercase()
        return when (ProxyProtocols.normalize(scheme)) {
            "vmess" -> parseVmess(link)
            "vless" -> parseVless(link)
            "trojan" -> parseTrojan(link)
            "ss" -> parseShadowsocks(link)
            "hysteria2" -> parseHysteria2(link)
            "tuic" -> parseTuic(link)
            "socks" -> parseSocks(link)
            "http" -> parseHttp(link)
            else -> null
        }
    }

    // ---------------------------------------------------------------- vmess

    private fun parseVmess(link: String): ProxyNode? {
        val payload = link.substringAfter("://").substringBefore("#")
        val json = B64.decodeToString(payload) ?: return null
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }
            .getOrNull() ?: return null

        val server = obj.str("add") ?: return null
        val port = obj.str("port")?.toIntOrNull() ?: return null
        val net = obj.str("net")?.lowercase()
        val tlsFlag = obj.str("tls")?.lowercase() == "tls"

        return ProxyNode(
            name = obj.str("ps") ?: "$server:$port",
            type = "vmess",
            server = server,
            port = port,
            uuid = obj.str("id"),
            method = obj.str("scy") ?: "auto",
            alterId = obj.str("aid")?.toIntOrNull() ?: 0,
            tls = tlsFlag,
            sni = obj.str("sni")?.takeIf { it.isNotBlank() },
            fingerprint = obj.str("fp")?.takeIf { it.isNotBlank() },
            network = net,
            wsPath = obj.str("path")?.takeIf { it.isNotBlank() },
            wsHost = obj.str("host")?.takeIf { it.isNotBlank() },
            grpcServiceName = if (net == "grpc") obj.str("path")?.takeIf { it.isNotBlank() } else null,
        )
    }

    // ---------------------------------------------------------------- vless

    private fun parseVless(link: String): ProxyNode? {
        val parts = splitLink(link) ?: return null
        val security = parts.query["security"]?.lowercase()
        val net = parts.query["type"]?.lowercase()
        val reality = security == "reality"

        return ProxyNode(
            name = parts.fragment,
            type = "vless",
            server = parts.host,
            port = parts.port,
            uuid = parts.userInfo,
            flow = parts.query["flow"]?.takeIf { it.isNotBlank() },
            tls = security == "tls" || security == "xtls" || reality,
            sni = parts.query["sni"]?.takeIf { it.isNotBlank() } ?: parts.query["host"],
            insecure = parts.query["allowinsecure"] == "1" || parts.query["insecure"] == "1",
            fingerprint = parts.query["fp"]?.takeIf { it.isNotBlank() },
            realityPublicKey = parts.query["pbk"]?.takeIf { it.isNotBlank() },
            realityShortId = parts.query["sid"],
            network = net,
            wsPath = parts.query["path"]?.takeIf { it.isNotBlank() },
            wsHost = parts.query["host"]?.takeIf { it.isNotBlank() },
            grpcServiceName = parts.query["servicename"]?.takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------------- trojan

    private fun parseTrojan(link: String): ProxyNode? {
        val parts = splitLink(link) ?: return null
        val net = parts.query["type"]?.lowercase()
        return ProxyNode(
            name = parts.fragment,
            type = "trojan",
            server = parts.host,
            port = parts.port,
            password = parts.userInfo,
            tls = true,
            sni = parts.query["sni"]?.takeIf { it.isNotBlank() } ?: parts.query["peer"],
            insecure = parts.query["allowinsecure"] == "1" || parts.query["insecure"] == "1",
            fingerprint = parts.query["fp"]?.takeIf { it.isNotBlank() },
            network = net,
            wsPath = parts.query["path"]?.takeIf { it.isNotBlank() },
            wsHost = parts.query["host"]?.takeIf { it.isNotBlank() },
            grpcServiceName = parts.query["servicename"]?.takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------- shadowsocks

    private fun parseShadowsocks(link: String): ProxyNode? {
        val body = link.substringAfter("://")
        val fragment = body.substringAfter("#", "")
        val withoutFragment = body.substringBefore("#")
        val queryString = withoutFragment.substringAfter("?", "")
        val core = withoutFragment.substringBefore("?")

        var method: String?
        var password: String
        var host: String
        var port: Int

        if (core.contains("@")) {
            // SIP002: ss://base64(method:password)@host:port
            val userInfo = B64.decodeToString(core.substringBeforeLast("@"))
                ?: return null
            method = userInfo.substringBefore(":")
            password = userInfo.substringAfter(":", "")
            val authority = core.substringAfterLast("@")
            host = authority.substringBeforeLast(":")
            port = authority.substringAfterLast(":").toIntOrNull() ?: return null
        } else {
            // Legacy: ss://base64(method:password@host:port)
            val decoded = B64.decodeToString(core) ?: return null
            val userInfo = decoded.substringBeforeLast("@")
            method = userInfo.substringBefore(":")
            password = userInfo.substringAfter(":", "")
            val authority = decoded.substringAfterLast("@")
            host = authority.substringBeforeLast(":")
            port = authority.substringAfterLast(":").toIntOrNull() ?: return null
        }

        if (method.isNullOrBlank() || host.isBlank() || port <= 0) return null
        method = ProxyProtocols.ssMethod(method)

        val query = parseQuery(queryString)
        val pluginRaw = query["plugin"]
        var plugin: String? = null
        var pluginOpts: String? = null
        if (!pluginRaw.isNullOrBlank()) {
            val parts = pluginRaw.split(";")
            plugin = parts.firstOrNull()?.takeIf { it.isNotBlank() }
            pluginOpts = parts.drop(1).joinToString(";").takeIf { it.isNotBlank() }
        }

        return ProxyNode(
            name = urlDecode(fragment).ifBlank { "$host:$port" },
            type = "ss",
            server = host,
            port = port,
            password = password,
            method = method,
            plugin = plugin,
            pluginOpts = pluginOpts,
        )
    }

    // ------------------------------------------------------------ hysteria2

    private fun parseHysteria2(link: String): ProxyNode? {
        val parts = splitLink(link) ?: return null
        val auth = if (parts.userInfo.contains(":")) {
            parts.userInfo.substringAfter(":")
        } else {
            parts.userInfo
        }
        return ProxyNode(
            name = parts.fragment,
            type = "hysteria2",
            server = parts.host,
            port = parts.port,
            password = auth,
            tls = true,
            sni = parts.query["sni"]?.takeIf { it.isNotBlank() } ?: parts.query["peer"],
            insecure = parts.query["insecure"] == "1" || parts.query["allowinsecure"] == "1",
            obfs = parts.query["obfs"]?.takeIf { it.isNotBlank() && it != "none" },
            obfsPassword = parts.query["obfs-password"] ?: parts.query["obfs_password"],
        )
    }

    // ----------------------------------------------------------------- tuic

    private fun parseTuic(link: String): ProxyNode? {
        val parts = splitLink(link) ?: return null
        val uuid = parts.userInfo.substringBefore(":").takeIf { it.isNotBlank() }
        val password = parts.userInfo.substringAfter(":", "").takeIf { it.isNotBlank() }
        return ProxyNode(
            name = parts.fragment,
            type = "tuic",
            server = parts.host,
            port = parts.port,
            uuid = uuid,
            password = password,
            tls = true,
            sni = parts.query["sni"]?.takeIf { it.isNotBlank() },
            insecure = parts.query["allow_insecure"] == "1" || parts.query["insecure"] == "1",
            congestionControl = parts.query["congestion_control"]?.takeIf { it.isNotBlank() },
        )
    }

    // --------------------------------------------------------------- socks

    private fun parseSocks(link: String): ProxyNode? {
        val parts = splitLink(link) ?: return null
        // userinfo may be plain user:pass or base64(user:pass)
        var userInfo = parts.userInfo
        if (!userInfo.contains(":")) {
            B64.decodeToString(userInfo)?.let { userInfo = it }
        }
        return ProxyNode(
            name = parts.fragment,
            type = "socks",
            server = parts.host,
            port = parts.port,
            username = userInfo.substringBefore(":", "").takeIf { it.isNotBlank() },
            password = userInfo.substringAfter(":", "").takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------------- http

    private fun parseHttp(link: String): ProxyNode? {
        val parts = splitLink(link) ?: return null
        return ProxyNode(
            name = parts.fragment,
            type = "http",
            server = parts.host,
            port = parts.port,
            tls = parts.query["security"]?.lowercase() == "tls",
            username = parts.userInfo.substringBefore(":", "").takeIf { it.isNotBlank() },
            password = parts.userInfo.substringAfter(":", "").takeIf { it.isNotBlank() },
        )
    }

    // ------------------------------------------------------------- helpers

    private data class LinkParts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
    )

    private fun splitLink(link: String): LinkParts? {
        var rest = link.substringAfter("://")
        val fragment = urlDecode(rest.substringAfter("#", ""))
        rest = rest.substringBefore("#")
        val queryString = rest.substringAfter("?", "")
        rest = rest.substringBefore("?")

        val userInfo = if (rest.contains("@")) urlDecode(rest.substringBeforeLast("@")) else ""
        val authority = rest.substringAfterLast("@")

        val (host, port) = splitHostPort(authority) ?: return null
        if (host.isBlank() || port <= 0) return null

        return LinkParts(
            userInfo = userInfo,
            host = host,
            port = port,
            query = parseQuery(queryString).mapKeys { it.key.lowercase() },
            fragment = fragment.ifBlank { "$host:$port" },
        )
    }

    /** Bare IPv6 literals appear in authority as [::1]:443 — plain split breaks on them. */
    private fun splitHostPort(authority: String): Pair<String, Int>? {
        if (authority.isBlank()) return null
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end <= 0) return null
            val host = authority.substring(1, end)
            val port = authority.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return host to port
        }
        val idx = authority.lastIndexOf(':')
        if (idx <= 0) return null
        val host = authority.substring(0, idx)
        val port = authority.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in query.split("&")) {
            if (pair.isBlank()) continue
            val k = urlDecode(pair.substringBefore("="))
            val v = urlDecode(pair.substringAfter("=", ""))
            if (k.isNotBlank()) map[k] = v
        }
        return map
    }

    private fun urlDecode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
