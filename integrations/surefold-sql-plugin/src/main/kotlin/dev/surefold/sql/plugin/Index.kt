package dev.surefold.sql.plugin

/** Spec 0003: codec objects earlier rounds compiled, kept beside the classes so an incremental round finds them. */
internal const val INDEX_PATH = "META-INF/surefold/sql.index"

private const val INDEX_VERSION = "v1"

private const val CODEC_FIELDS = 3

/** [placeholders] is null for a codec whose count is not a constant, so a later round reports it as this one would. */
internal class IndexedCodec(val classId: String, val placeholders: Int?)

/** Null when the text is not an index this plugin version wrote. */
internal fun decodeIndex(text: String): List<IndexedCodec>? {
    val lines = text.lines().filter { it.isNotEmpty() }
    if (lines.firstOrNull() != INDEX_VERSION) return null
    return lines.drop(1).map { line ->
        val (kind, id, count) = line.split('\t').takeIf { it.size == CODEC_FIELDS } ?: return null
        if (kind != "codec") return null
        IndexedCodec(id, count.toIntOrNull())
    }
}

internal fun encodeIndex(codecs: List<IndexedCodec>): String =
    (listOf(INDEX_VERSION) + codecs.map { "codec\t${it.classId}\t${it.placeholders ?: "-"}" })
        .joinToString("\n", postfix = "\n")
