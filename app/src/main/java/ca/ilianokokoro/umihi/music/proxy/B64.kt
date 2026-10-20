package ca.ilianokokoro.umihi.music.proxy

/**
 * Tiny dependency-free base64 helper.
 *
 * Deliberately not android.util.Base64 (needs a device) and not java.util.Base64
 * (API 26+, while minSdk here is 24). Accepts URL-safe alphabets and missing padding,
 * which is what real-world vmess/ss links look like.
 */
object B64 {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val reverse: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
        table['-'.code] = 62
        table['_'.code] = 63
    }

    fun decode(input: String): ByteArray? {
        val cleaned = input.filterNot { it.isWhitespace() || it == '=' }
        if (cleaned.isEmpty()) return null

        val out = ByteArray(cleaned.length * 3 / 4 + 2)
        var buffer = 0
        var bits = 0
        var pos = 0

        for (c in cleaned) {
            val code = c.code
            val value = if (code < 128) reverse[code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[pos++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(pos)
    }

    fun decodeToString(input: String): String? =
        decode(input)?.toString(Charsets.UTF_8)
}
