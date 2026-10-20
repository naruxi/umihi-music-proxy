package ca.ilianokokoro.umihi.music.proxy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds a minimal sing-box config that exposes a local mixed (HTTP+SOCKS) inbound and
 * sends everything through the selected proxy node.
 *
 * Deliberately tiny: no TUN, no rule sets, no inbound sniffing. Umihi only needs a local
 * port that its own HTTP client and player can point at, which keeps the config valid
 * across sing-box versions and avoids needing VpnService consent.
 */
object SingBoxConfig {

    const val INBOUND_TAG = "umihi-in"
    const val PROXY_TAG = "proxy"
    const val DIRECT_TAG = "direct"
    const val DNS_TAG = "dns-local"

    /** Bootstrap resolver used to resolve the proxy server hostname itself. */
    private const val BOOTSTRAP_DNS = "223.5.5.5"

    fun build(node: ProxyNode, listenPort: Int): String {
        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "warn")
                put("timestamp", true)
            })

            put("dns", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "udp")
                        put("tag", DNS_TAG)
                        put("server", BOOTSTRAP_DNS)
                    })
                })
            })

            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("type", "mixed")
                    put("tag", INBOUND_TAG)
                    put("listen", "127.0.0.1")
                    put("listen_port", listenPort)
                    put("sniff", false)
                })
            })

            put("outbounds", buildJsonArray {
                add(outbound(node))
                add(buildJsonObject {
                    put("type", DIRECT_TAG)
                    put("tag", DIRECT_TAG)
                })
            })

            put("route", buildJsonObject {
                put("rules", buildJsonArray { })
                put("final", PROXY_TAG)
                put("default_domain_resolver", DNS_TAG)
            })
        }

        return root.toString()
    }

    private fun outbound(node: ProxyNode): JsonObject = buildJsonObject {
        put("tag", PROXY_TAG)
        when (node.type) {
            "ss" -> putShadowsocks(node)
            "vmess" -> putVmess(node)
            "vless" -> putVless(node)
            "trojan" -> putTrojan(node)
            "hysteria2" -> putHysteria2(node)
            "tuic" -> putTuic(node)
            "socks" -> putSocks(node)
            "http" -> putHttp(node)
            else -> error("Unsupported proxy type: ${node.type}")
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCommon(node: ProxyNode) {
        put("server", node.server)
        put("server_port", node.port)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putShadowsocks(node: ProxyNode) {
        put("type", "shadowsocks")
        putCommon(node)
        put("method", node.method ?: "aes-256-gcm")
        put("password", node.password ?: "")

        // Clash ships plugin names that differ from the sing-box ones.
        when (node.plugin?.lowercase()) {
            null, "" -> Unit
            "obfs" -> {
                put("plugin", "obfs-local")
                put("plugin_opts", node.pluginOpts?.let(::clashObfsOpts) ?: "obfs=http")
            }

            "v2ray-plugin" -> {
                put("plugin", "v2ray-plugin")
                node.pluginOpts?.let { put("plugin_opts", it) }
            }
        }
    }

    /** Clash: `mode=http;host=a.com` -> sing-box: `obfs=http;obfs-host=a.com` */
    private fun clashObfsOpts(opts: String): String {
        val mapped = opts.split(";")
            .filter { it.isNotBlank() }
            .map { pair ->
                val key = pair.substringBefore("=")
                val value = pair.substringAfter("=", "")
                when (key) {
                    "mode" -> "obfs=$value"
                    "host" -> "obfs-host=$value"
                    else -> pair
                }
            }
        return mapped.joinToString(";")
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putVmess(node: ProxyNode) {
        put("type", "vmess")
        putCommon(node)
        put("uuid", node.uuid ?: "")
        put("security", node.method?.takeIf { it.isNotBlank() } ?: "auto")
        put("alter_id", node.alterId)
        putTransport(node)
        putTls(node)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putVless(node: ProxyNode) {
        put("type", "vless")
        putCommon(node)
        put("uuid", node.uuid ?: "")
        node.flow?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
        putTransport(node)
        putTls(node)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTrojan(node: ProxyNode) {
        put("type", "trojan")
        putCommon(node)
        put("password", node.password ?: "")
        putTransport(node)
        putTls(node.copy(tls = true))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putHysteria2(node: ProxyNode) {
        put("type", "hysteria2")
        putCommon(node)
        put("password", node.password ?: "")
        node.upMbps?.let { put("up_mbps", it) }
        node.downMbps?.let { put("down_mbps", it) }
        if (!node.obfs.isNullOrBlank() && !node.obfsPassword.isNullOrBlank()) {
            put("obfs", buildJsonObject {
                put("type", node.obfs)
                put("password", node.obfsPassword)
            })
        }
        putTls(node.copy(tls = true))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTuic(node: ProxyNode) {
        put("type", "tuic")
        putCommon(node)
        node.uuid?.takeIf { it.isNotBlank() }?.let { put("uuid", it) }
        node.password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
        node.congestionControl?.takeIf { it.isNotBlank() }?.let { put("congestion_control", it) }
        putTls(node.copy(tls = true))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSocks(node: ProxyNode) {
        // sing-box's socks outbound has no TLS field, yet plenty of Clash subscriptions ship
        // "socks5 over TLS" nodes — usually CDN-fronted relays whose server name is disguised
        // as a Huawei / Xiaohongshu / iQIYI hostname. Dropping the flag makes the core send a
        // cleartext SOCKS5 greeting to a port that only speaks TLS; the server answers with a
        // TLS alert (the bytes `15 03 03`, i.e. an alert record) and every request fails while
        // the same node works fine in Clash, which honours `tls: true`.
        //
        // Those relays answer HTTP CONNECT over the very same TLS port, so an http outbound is
        // an equivalent way to express them. Verified end to end against a live node: TLS to
        // the relay, CONNECT with Basic auth, then a 200 with a real response body from the
        // YouTube Music API.
        if (node.tls) {
            putHttp(node)
            return
        }

        put("type", "socks")
        putCommon(node)
        put("version", "5")
        node.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
        node.password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putHttp(node: ProxyNode) {
        put("type", "http")
        putCommon(node)
        node.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
        node.password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
        if (node.tls) putTls(node)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTransport(node: ProxyNode) {
        when (node.network?.lowercase()) {
            "ws" -> put("transport", buildJsonObject {
                put("type", "ws")
                put("path", node.wsPath?.takeIf { it.isNotBlank() } ?: "/")
                node.wsHost?.takeIf { it.isNotBlank() }?.let { host ->
                    put("headers", buildJsonObject { put("Host", host) })
                }
                // Matches the usual Clash/v2ray behaviour and improves first-packet latency.
                put("max_early_data", 2048)
                put("early_data_header_name", "Sec-WebSocket-Protocol")
            })

            "grpc" -> put("transport", buildJsonObject {
                put("type", "grpc")
                node.grpcServiceName?.takeIf { it.isNotBlank() }?.let { put("service_name", it) }
            })

            "h2", "http" -> put("transport", buildJsonObject {
                put("type", "http")
                node.wsHost?.takeIf { it.isNotBlank() }?.let { host ->
                    put("host", buildJsonArray { add(host) })
                }
                put("path", node.wsPath?.takeIf { it.isNotBlank() } ?: "/")
            })
            // "tcp" and null need no transport block
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTls(node: ProxyNode) {
        if (!node.tls) return

        put("tls", buildJsonObject {
            put("enabled", true)
            // REALITY requires an explicit server_name; fall back to the connect host.
            val sni = node.sni?.takeIf { it.isNotBlank() }
                ?: if (!node.realityPublicKey.isNullOrBlank()) node.server else null
            sni?.let { put("server_name", it) }
            if (node.insecure) put("insecure", true)

            val reality = !node.realityPublicKey.isNullOrBlank()
            // sing-box refuses a REALITY client without uTLS ("uTLS is required by reality
            // client"), and plenty of Clash subscriptions omit client-fingerprint, so default
            // to the same fingerprint Clash itself uses.
            val fingerprint = node.fingerprint?.takeIf { it.isNotBlank() }
                ?: if (reality) "chrome" else null
            fingerprint?.let { fp ->
                put("utls", buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", fp)
                })
            }

            if (reality) {
                put("reality", buildJsonObject {
                    put("enabled", true)
                    put("public_key", node.realityPublicKey!!)
                    node.realityShortId?.takeIf { it.isNotBlank() }?.let { put("short_id", it) }
                })
            }
        })
    }
}
