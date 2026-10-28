package dev.personal.airplayrx

/**
 * Decodes a DNS-SD TXT record (RFC 6763 §6: a sequence of length-prefixed "key=value" strings)
 * as produced by UxPlay through the dns_sd shim. Order is preserved; a key without '=' maps to
 * null. Malformed trailing bytes are ignored rather than thrown, since the input comes from
 * native code we control.
 */
object TxtRecord {
    fun parse(bytes: ByteArray): List<Pair<String, String?>> {
        val entries = ArrayList<Pair<String, String?>>()
        var i = 0
        while (i < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            val start = i + 1
            val end = start + len
            if (end > bytes.size) break
            if (len > 0) {
                val entry = String(bytes, start, len, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                entries += if (eq < 0) entry to null else entry.substring(0, eq) to entry.substring(eq + 1)
            }
            i = end
        }
        return entries
    }
}
