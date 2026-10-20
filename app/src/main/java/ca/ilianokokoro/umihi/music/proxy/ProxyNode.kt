package ca.ilianokokoro.umihi.music.proxy

import kotlinx.serialization.Serializable

/**
 * A single proxy server, normalized from any supported subscription format
 * (Clash YAML or share-link list) into a sing-box-agnostic shape.
 */
@Serializable
data class ProxyNode(
    val name: String,
    /** One of: ss / vmess / vless / trojan / hysteria2 / tuic / socks / http */
    val type: String,
    val server: String,
    val port: Int,
    /** ss password, trojan password, hysteria2 auth, socks/http password */
    val password: String? = null,
    /** vmess / vless uuid, tuic uuid */
    val uuid: String? = null,
    /** ss cipher, vmess security */
    val method: String? = null,
    val alterId: Int = 0,
    val flow: String? = null,
    val tls: Boolean = false,
    val sni: String? = null,
    val insecure: Boolean = false,
    val fingerprint: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    /** ws / grpc / h2 / http / tcp */
    val network: String? = null,
    val wsPath: String? = null,
    val wsHost: String? = null,
    val grpcServiceName: String? = null,
    /** hysteria2 obfs type: salamander */
    val obfs: String? = null,
    val obfsPassword: String? = null,
    /** ss plugin, e.g. obfs / v2ray-plugin */
    val plugin: String? = null,
    val pluginOpts: String? = null,
    /** socks / http auth */
    val username: String? = null,
    val congestionControl: String? = null,
    val upMbps: Int? = null,
    val downMbps: Int? = null,
    /** true when the subscription lists it as the provider's recommended default */
    val isDefault: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { "$server:$port" }

    /** Stable identity used for persisting the user's selection across refresh. */
    val key: String get() = "$type|$server|$port|${uuid ?: password ?: ""}|$name"

    fun isValid(): Boolean = when (type) {
        "ss" -> server.isNotBlank() && port > 0 && !password.isNullOrEmpty() && !method.isNullOrEmpty()
        "vmess", "vless" -> server.isNotBlank() && port > 0 && !uuid.isNullOrEmpty()
        "trojan" -> server.isNotBlank() && port > 0 && !password.isNullOrEmpty()
        "hysteria2" -> server.isNotBlank() && port > 0 && !password.isNullOrEmpty()
        "tuic" -> server.isNotBlank() && port > 0 && !(uuid.isNullOrEmpty() && password.isNullOrEmpty())
        "socks", "http" -> server.isNotBlank() && port > 0
        else -> false
    }
}

object ProxyProtocols {
    val SUPPORTED = setOf("ss", "vmess", "vless", "trojan", "hysteria2", "tuic", "socks", "http")

    /** Clash type aliases -> canonical name */
    fun normalize(raw: String): String? = when (raw.trim().lowercase()) {
        "ss", "shadowsocks" -> "ss"
        "vmess" -> "vmess"
        "vless" -> "vless"
        "trojan" -> "trojan"
        "hysteria2", "hy2" -> "hysteria2"
        "tuic" -> "tuic"
        "socks", "socks5" -> "socks"
        "http", "https" -> "http"
        else -> null
    }

    /** Clash cipher name -> sing-box shadowsocks method name */
    fun ssMethod(clashCipher: String): String = when (clashCipher.trim().lowercase()) {
        "aes-128-gcm" -> "aes-128-gcm"
        "aes-192-gcm" -> "aes-192-gcm"
        "aes-256-gcm" -> "aes-256-gcm"
        "aes-128-cfb" -> "aes-128-cfb"
        "aes-192-cfb" -> "aes-192-cfb"
        "aes-256-cfb" -> "aes-256-cfb"
        "aes-128-ctr" -> "aes-128-ctr"
        "aes-192-ctr" -> "aes-192-ctr"
        "aes-256-ctr" -> "aes-256-ctr"
        "rc4-md5" -> "rc4-md5"
        "chacha20-ietf" -> "chacha20-ietf"
        "chacha20-ietf-poly1305" -> "chacha20-ietf-poly1305"
        "chacha20-poly1305" -> "chacha20-poly1305"
        "xchacha20-ietf-poly1305" -> "xchacha20-ietf-poly1305"
        "2022-blake3-aes-128-gcm" -> "2022-blake3-aes-128-gcm"
        "2022-blake3-aes-256-gcm" -> "2022-blake3-aes-256-gcm"
        "2022-blake3-chacha20-poly1305" -> "2022-blake3-chacha20-poly1305"
        "none" -> "none"
        else -> clashCipher.trim()
    }
}
