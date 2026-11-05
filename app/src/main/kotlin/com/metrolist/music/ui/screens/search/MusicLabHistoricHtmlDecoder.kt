package com.metrolist.music.ui.screens.search

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/**
 * Historical HitParadeItalia pages mix UTF-8 and legacy Western encodings.
 *
 * ResponseBody.string() assumes UTF-8 when no charset is advertised and
 * irreversibly replaces Windows-1252/ISO-8859-1 accented bytes with U+FFFD.
 * Decode bytes BEFORE Jsoup/regex parsing, with a strict UTF-8 validation.
 * No network requests and no chart-data substitutions happen here.
 */
internal object MusicLabHistoricHtmlDecoder {
    private val metaCharset = Regex(
        """(?i)\bcharset\s*=\s*['"]?([A-Za-z][A-Za-z0-9._-]{1,35})"""
    )
    private val headerCharset = Regex(
        """(?i)(?:^|;)\s*charset\s*=\s*["']?([A-Za-z][A-Za-z0-9._-]{1,35})"""
    )
    private val broken = Regex("[\uFFFD]")
    private val mojibake = Regex("(?i)(?:Ã[\u0080-\u00BF]|Â[\u0080-\u00BF]|â[\u0080-\u00BF])")

    internal fun corrupted(value: String): Boolean =
        broken.containsMatchIn(value) || mojibake.containsMatchIn(value)

    private fun charset(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        return runCatching {
            // Web pages often declare ISO-8859-1 but contain Windows-1252
            // smart punctuation in the 0x80-0x9F range.
            when (name.trim().lowercase(Locale.ROOT)) {
                "latin1", "latin-1", "iso-8859-1", "iso8859-1", "windows-1252", "cp1252" ->
                    Charset.forName("windows-1252")
                else -> Charset.forName(name)
            }
        }.getOrNull()
    }

    private fun strictly(bytes: ByteArray, charset: Charset): String? =
        try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) { null }

    internal fun decode(bytes: ByteArray, contentType: String? = null): String {
        if (bytes.isEmpty()) return ""
        val utf8 = Charsets.UTF_8
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Normalizer.normalize(
                bytes.copyOfRange(3, bytes.size).toString(utf8), Normalizer.Form.NFC
            )
        }
        val header = charset(headerCharset.find(contentType.orEmpty())?.groupValues?.get(1))
        // ASCII-compatible header scans can safely read arbitrary legacy bytes.
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val meta = charset(metaCharset.find(head)?.groupValues?.get(1))
        val strictUtf8 = strictly(bytes, utf8)
        val legacy = charset("windows-1252")!!
        // A valid UTF-8 body is never misread as 8-bit if no trustworthy meta
        // contradicts it; this also preserves genuine UTF-8 Arabic/Greek etc.
        val selected = when {
            meta != null && meta != utf8 -> {
                val text = strictly(bytes, meta)
                if (strictUtf8 != null && text != null && mojibake.containsMatchIn(text))
                    strictUtf8 else text ?: strictly(bytes, legacy) ?: strictUtf8
            }
            strictUtf8 != null -> strictUtf8
            header != null -> strictly(bytes, header) ?: strictly(bytes, legacy)
            else -> strictly(bytes, legacy)
        } ?: String(bytes, Charsets.ISO_8859_1)
        return Normalizer.normalize(selected, Normalizer.Form.NFC)
    }
}
