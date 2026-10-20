package ca.ilianokokoro.umihi.music.proxy

/**
 * Entry point for turning whatever a subscription URL returns into nodes.
 *
 * Providers hand out at least four shapes in the wild, so this sniffs rather than trusting
 * the URL: inline Clash YAML, a base64-wrapped Clash YAML, a plain share-link list, and a
 * base64-wrapped share-link list.
 */
object SubscriptionParser {

    fun parse(raw: String): List<ProxyNode> {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return emptyList()

        parseDirect(text)?.let { return it }

        // Base64 wrapped payloads (very common for share-link subscriptions)
        val decoded = B64.decodeToString(text)
        if (decoded != null && decoded.isNotBlank() && decoded != text) {
            parseDirect(decoded)?.let { return it }
        }

        return emptyList()
    }

    private fun parseDirect(text: String): List<ProxyNode>? {
        if (ClashYamlParser.looksLikeClash(text)) {
            ClashYamlParser.parse(text).takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (ShareLinkParser.looksLikeLinks(text)) {
            ShareLinkParser.parseAll(text).takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Some providers serve bare links without a trailing newline; try anyway when the
        // payload clearly contains a known scheme.
        if (text.contains("://")) {
            ShareLinkParser.parseAll(text).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }
}
