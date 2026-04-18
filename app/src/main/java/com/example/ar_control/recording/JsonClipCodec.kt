package com.example.ar_control.recording

internal sealed interface JsonClipCatalogParseResult {
    data object Empty : JsonClipCatalogParseResult
    data class Success(val clips: List<RecordedClip>) : JsonClipCatalogParseResult
    data class Malformed(val reason: String) : JsonClipCatalogParseResult
}

internal object JsonClipCodec {

    fun decodeCatalog(raw: String): JsonClipCatalogParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return JsonClipCatalogParseResult.Empty
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return JsonClipCatalogParseResult.Malformed("Catalog is not a JSON array")
        }

        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return JsonClipCatalogParseResult.Empty

        return try {
            val clips = parseClipArray(inner)
            if (clips.isEmpty()) {
                JsonClipCatalogParseResult.Empty
            } else {
                JsonClipCatalogParseResult.Success(clips)
            }
        } catch (error: IllegalArgumentException) {
            JsonClipCatalogParseResult.Malformed(error.message ?: "Malformed catalog")
        } catch (error: RuntimeException) {
            JsonClipCatalogParseResult.Malformed(error.message ?: "Malformed catalog")
        }
    }

    fun encodeCatalog(clips: List<RecordedClip>): String {
        return clips.joinToString(prefix = "[", postfix = "]") { clip -> clip.toJson() }
    }

    private fun parseClipArray(content: String): List<RecordedClip> {
        val clips = ArrayList<RecordedClip>()
        var index = 0

        index = skipWhitespace(content, index)
        if (index >= content.length) return clips

        while (true) {
            if (content[index] != '{') {
                throw IllegalArgumentException("Expected object entry")
            }

            val endIndex = findObjectEnd(content, index)
            val chunk = content.substring(index, endIndex + 1)
            clips += chunk.toRecordedClip()
            index = skipWhitespace(content, endIndex + 1)

            if (index >= content.length) {
                return clips
            }
            if (content[index] != ',') {
                throw IllegalArgumentException("Expected array separator")
            }

            index = skipWhitespace(content, index + 1)
            if (index >= content.length) {
                throw IllegalArgumentException("Trailing array separator")
            }
        }
    }

    private fun findObjectEnd(content: String, startIndex: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false

        for (index in startIndex until content.length) {
            val char = content[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }

        throw IllegalArgumentException("Unterminated object")
    }

    private fun skipWhitespace(content: String, startIndex: Int): Int {
        var index = startIndex
        while (index < content.length && content[index].isWhitespace()) {
            index += 1
        }
        return index
    }

    private fun RecordedClip.toJson(): String = buildString {
        append('{')
        appendJsonField("id", id)
        append(',')
        appendJsonField("filePath", filePath)
        append(',')
        appendJsonField("createdAtEpochMillis", createdAtEpochMillis)
        append(',')
        appendJsonField("durationMillis", durationMillis)
        append(',')
        appendJsonField("width", width)
        append(',')
        appendJsonField("height", height)
        append(',')
        appendJsonField("fileSizeBytes", fileSizeBytes)
        append(',')
        appendJsonField("mimeType", mimeType)
        append('}')
    }

    private fun String.toRecordedClip(): RecordedClip = RecordedClip(
        id = stringField("id"),
        filePath = stringField("filePath"),
        createdAtEpochMillis = longField("createdAtEpochMillis"),
        durationMillis = longField("durationMillis"),
        width = intField("width"),
        height = intField("height"),
        fileSizeBytes = longField("fileSizeBytes"),
        mimeType = stringField("mimeType")
    )

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append('"')
        append(escapeJson(name))
        append("\":\"")
        append(escapeJson(value))
        append('"')
    }

    private fun StringBuilder.appendJsonField(name: String, value: Int) {
        append('"')
        append(escapeJson(name))
        append("\":")
        append(value)
    }

    private fun StringBuilder.appendJsonField(name: String, value: Long) {
        append('"')
        append(escapeJson(name))
        append("\":")
        append(value)
    }

    private fun String.stringField(name: String): String {
        val match = stringFieldRegex(name).find(this)
            ?: error("Missing string field: $name")
        return unescapeJson(match.groupValues[1])
    }

    private fun String.intField(name: String): Int {
        val match = numberFieldRegex(name).find(this)
            ?: error("Missing int field: $name")
        return match.groupValues[1].toInt()
    }

    private fun String.longField(name: String): Long {
        val match = numberFieldRegex(name).find(this)
            ?: error("Missing long field: $name")
        return match.groupValues[1].toLong()
    }

    private fun stringFieldRegex(name: String): Regex {
        return Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    }

    private fun numberFieldRegex(name: String): Regex {
        return Regex("\"${Regex.escape(name)}\"\\s*:\\s*(-?\\d+)")
    }

    private fun escapeJson(value: String): String {
        val builder = StringBuilder(value.length)
        for (char in value) {
            when (char) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        builder.append("\\u")
                        builder.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(char)
                    }
                }
            }
        }
        return builder.toString()
    }

    private fun unescapeJson(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\') {
                builder.append(char)
                index += 1
                continue
            }

            index += 1
            if (index >= value.length) break
            when (val escaped = value[index]) {
                '"', '\\', '/' -> builder.append(escaped)
                'b' -> builder.append('\b')
                'f' -> builder.append('\u000C')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                'u' -> {
                    if (index + 4 < value.length) {
                        val hex = value.substring(index + 1, index + 5)
                        builder.append(hex.toInt(16).toChar())
                        index += 4
                    } else {
                        throw IllegalArgumentException("Invalid unicode escape")
                    }
                }
                else -> throw IllegalArgumentException("Invalid escape sequence")
            }
            index += 1
        }
        return builder.toString()
    }
}
