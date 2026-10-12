package com.chethan616.clearpdf.utils

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decode a text file's bytes: UTF-8 (BOM stripped) when valid, otherwise CP949 — the Windows
 * superset of EUC-KR that Korean `.txt` files are usually saved in. Charset names differ between
 * the JVM and Android's ICU, so the first available alias wins.
 */
internal fun decodeText(bytes: ByteArray): String {
    val hasBom = bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (_: CharacterCodingException) {
        String(body, koreanCharset)
    }
}

/**
 * Read at most [maxBytes] from [input]; the flag says whether anything was left unread. A cut
 * input is trimmed back to its last '\n' so no multi-byte character is split — 0x0A is never a
 * continuation byte in UTF-8 or CP949, and a split character would push [decodeText] off UTF-8.
 */
internal fun readCapped(input: InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
    val buf = ByteArray(maxBytes)
    var n = 0
    while (n < maxBytes) {
        val r = input.read(buf, n, maxBytes - n)
        if (r < 0) return buf.copyOf(n) to false
        n += r
    }
    if (input.read() < 0) return buf to false
    val lastNewline = buf.lastIndexOf('\n'.code.toByte())
    return buf.copyOf(if (lastNewline >= 0) lastNewline + 1 else n) to true
}

private val koreanCharset: Charset by lazy {
    listOf("x-windows-949", "MS949", "windows-949", "EUC-KR")
        .firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }
        ?: Charsets.ISO_8859_1
}
