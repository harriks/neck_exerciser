package com.spineexercise.timer

// ===================== Timing Configuration =====================
// All countdown durations (contract / relax / groups / prepare) live in a
// single JSON config so they can be tuned in one place.
//
// The Web version (spine_exercise_timer.html) shares the same JSON structure.
//
// Pure Kotlin (no Android / no third-party deps) so it can be unit-tested.

val DEFAULT_TIMING_JSON = """
{
  "prepareSec": 3,
  "stagePrepareSec": 5,
  "modes": {
    "gentle": [
      { "name": "温和发力", "dirs": ["右手", "左手"], "contractSec": 8, "relaxSec": 5, "groups": 4 }
    ],
    "isometric": [
      { "name": "正向抗阻", "dirs": ["右手", "左手"], "contractSec": 15, "relaxSec": 15, "groups": 3 },
      { "name": "侧向抗阻", "dirs": ["右手", "左手"], "contractSec": 15, "relaxSec": 15, "groups": 3 },
      { "name": "弹力带训练", "dirs": ["弹力带"], "contractSec": 15, "relaxSec": 30, "groups": 3 }
    ]
  }
}
"""

/**
 * Parsed timing configuration. Holds prepare seconds plus the stage list per mode.
 */
data class TimingConfig(
    val prepareSec: Int,
    // Seconds to get ready between workout stages (stage switch); 0 disables it.
    val stagePrepareSec: Int = DEFAULT_STAGE_PREPARE_SEC,
    val stages: Map<Mode, List<ExerciseStage>>,
) {
    companion object {
        /** Fallback for legacy saved configs that predate "stagePrepareSec". */
        const val DEFAULT_STAGE_PREPARE_SEC = 5

        /** Load from the default embedded JSON (used when the app starts / tests). */
        fun default(): TimingConfig = fromJson(DEFAULT_TIMING_JSON)

        /**
         * Parse a JSON document in the shared schema.
         * Minimal hand-rolled parser (no external deps); expects well-formed JSON
         * produced by DEFAULT_TIMING_JSON, throws on malformed input.
         */
        fun fromJson(json: String): TimingConfig {
            val p = JsonParser(json.trim()).root() as JObj
            val prepareSec = (p.get("prepareSec") as JNum).value.toInt()
            // Optional in legacy saved configs: fall back to the default.
            val stagePrepare = (p.get("stagePrepareSec") as? JNum)?.value?.toInt()
                ?: DEFAULT_STAGE_PREPARE_SEC
            val modes = p.get("modes") as JObj
            val stages = mapOf(
                Mode.GENTLE to parseStages(modes.get("gentle") as JArr),
                Mode.ISOMETRIC to parseStages(modes.get("isometric") as JArr),
            )
            return TimingConfig(prepareSec = prepareSec, stagePrepareSec = stagePrepare, stages = stages)
        }

        private fun parseStages(arr: JArr): List<ExerciseStage> =
            arr.items.map { it ->
                val o = it as JObj
                ExerciseStage(
                    name = (o.get("name") as JStr).value,
                    dirs = (o.get("dirs") as JArr).items.map { (it as JStr).value },
                    contractSec = (o.get("contractSec") as JNum).value.toInt(),
                    relaxSec = (o.get("relaxSec") as JNum).value.toInt(),
                    groups = (o.get("groups") as JNum).value.toInt(),
                )
            }
    }

    /**
     * Serialize back to the shared JSON schema (runtime settings editor saves
     * this; [fromJson] must round-trip it unchanged — see TimingConfigTest).
     */
    fun toJson(): String {
        fun stage(st: ExerciseStage) = buildString {
            append("{ \"name\": \"${st.name}\", \"dirs\": [")
            append(st.dirs.joinToString(", ") { "\"$it\"" })
            append("], \"contractSec\": ${st.contractSec}, \"relaxSec\": ${st.relaxSec}, \"groups\": ${st.groups} }")
        }
        val gentle = (stages[Mode.GENTLE] ?: emptyList()).joinToString(",\n      ") { stage(it) }
        val iso = (stages[Mode.ISOMETRIC] ?: emptyList()).joinToString(",\n      ") { stage(it) }
        return """
{
  "prepareSec": $prepareSec,
  "stagePrepareSec": $stagePrepareSec,
  "modes": {
    "gentle": [
      $gentle
    ],
    "isometric": [
      $iso
    ]
  }
}""".trimIndent()
    }
}

// ===================== Tiny JSON model =====================

private sealed class JVal
private data class JObj(val map: Map<String, JVal>) : JVal() {
    fun get(key: String): JVal? = map[key]
}
private data class JArr(val items: List<JVal>) : JVal()
private data class JStr(val value: String) : JVal()
private data class JNum(val value: Double) : JVal()

// ===================== Minimal JSON parser =====================
// Supports only: object, array, string, number, true/false/null.
// Handles Unicode \uXXXX and escaped quotes.

private class JsonParser(private val s: String) {
    private var i = 0

    fun root(): JVal { skipWs(); return parseValue() }

    private fun peek(): Char { return s[i] }

    private fun parseValue(): JVal {
        skipWs()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JStr(parseString())
            else -> parseLiteral()
        }
    }

    private fun parseObject(): JObj {
        i++ // '{'
        skipWs()
        if (peek() == '}') { i++; return JObj(mapOf()) }
        val m = linkedMapOf<String, JVal>()
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            m[key] = parseValue()
            skipWs()
            if (peek() == ',') { i++; continue }
            expect('}')
            break
        }
        return JObj(m)
    }

    private fun parseArray(): JArr {
        i++ // '['
        skipWs()
        if (peek() == ']') { i++; return JArr(listOf()) }
        val items = mutableListOf<JVal>()
        while (true) {
            items += parseValue()
            skipWs()
            if (peek() == ',') { i++; continue }
            expect(']')
            break
        }
        return JArr(items)
    }

    private fun parseString(): String {
        i++ // '"'
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '"') { i++; break }
            if (c == '\\') {
                i++
                val e = s[i]
                when (e) {
                    'n' -> { sb.append('\n'); i++ }
                    't' -> { sb.append('\t'); i++ }
                    'r' -> { sb.append('\r'); i++ }
                    '"' -> { sb.append('"'); i++ }
                    '\\' -> { sb.append('\\'); i++ }
                    '/' -> { sb.append('/'); i++ }
                    'u' -> {
                        val hex = s.substring(i + 1, minOf(i + 5, s.length))
                        sb.append(hex.toInt(16).toChar())
                        i += 5
                    }
                    else -> { sb.append(e); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun parseLiteral(): JVal {
        val start = i
        while (i < s.length && s[i] !in ",]}") i++
        val tok = s.substring(start, i).trim()
        return when {
            tok == "true" || tok == "false" || tok == "null" -> JNum(0.0) // unused in this schema
            else -> JNum(tok.toDouble())
        }
    }

    private fun expect(c: Char) {
        if (peek() != c) throw IllegalStateException("JSON: expected '$c' at $i")
        i++
    }

    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
}