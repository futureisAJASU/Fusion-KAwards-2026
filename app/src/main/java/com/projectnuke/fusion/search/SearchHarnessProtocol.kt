package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.model.AgenticWebSearchTool
import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.llm.runNonFatal
import org.json.JSONArray
import org.json.JSONObject

/** Strictly parsed model intent. The model never receives a callable transport. */
sealed interface SearchToolAction {
    data class Search(val queries: List<String>) : SearchToolAction
    data class Final(val answer: String) : SearchToolAction
    data object NoMoreSearch : SearchToolAction
    data object Unsupported : SearchToolAction
    data object InvalidToolCall : SearchToolAction
}

/** Privacy-safe parser outcome; error codes never include raw model text. */
data class SearchToolParseResult(
    val action: SearchToolAction,
    val errorCode: String? = null,
)

/**
 * Version 1 search action protocol for local structured intent and native
 * OpenAI function calls. Unknown fields, tools, types, and oversized payloads
 * are rejected before any search transport can execute.
 */
object SearchHarnessProtocol {
    const val MAX_ACTION_CHARS = 4_096
    const val MAX_QUERY_CHARS = 256
    const val MAX_ANSWER_CHARS = 1_024
    const val MAX_QUERIES_PER_ACTION = 2

    fun parseStructuredIntent(raw: String, truncated: Boolean = false): SearchToolParseResult =
        parseStructuredIntent(raw, jsonObjectParser = { JSONObject(it) }, truncated = truncated)

    internal fun parseStructuredIntent(
        raw: String,
        jsonObjectParser: (String) -> JSONObject,
        truncated: Boolean = false,
    ): SearchToolParseResult {
        if (truncated) return invalid("truncated")
        if (raw.isBlank()) return invalid("empty")
        if (raw.length > MAX_ACTION_CHARS) return invalid("oversized")
        if (!StrictJsonSyntax.isValid(raw)) return invalid("malformed_json")
        val json = runNonFatal { jsonObjectParser(raw) } ?: return invalid("malformed_json")
        return parseActionObject(json)
    }

    fun parseNativeToolCall(call: AiToolCall): SearchToolParseResult =
        parseNativeToolCall(call, jsonObjectParser = { JSONObject(it) })

    internal fun parseNativeToolCall(
        call: AiToolCall,
        jsonObjectParser: (String) -> JSONObject,
    ): SearchToolParseResult {
        if (call.id.isBlank() || call.id.length > 128) return invalid("missing_tool_call_id")
        if (call.name != AgenticWebSearchTool.NAME) return invalid("unsupported_tool")
        if (call.arguments.isBlank() || call.arguments.length > MAX_ACTION_CHARS) return invalid("invalid_arguments")
        if (!StrictJsonSyntax.isValid(call.arguments)) return invalid("malformed_arguments")
        val args = runNonFatal { jsonObjectParser(call.arguments) } ?: return invalid("malformed_arguments")
        if (!hasOnlyKeys(args, setOf("queries"))) return invalid("unknown_arguments")
        return parseQueries(args.opt("queries"))
    }

    private fun parseActionObject(json: JSONObject): SearchToolParseResult {
        val action = json.opt("action") as? String ?: return invalid("missing_action")
        return when (action) {
            "SEARCH" -> {
                if (!hasOnlyKeys(json, setOf("action", "queries"))) return invalid("unknown_arguments")
                parseQueries(json.opt("queries"))
            }
            "FINAL" -> {
                if (!hasOnlyKeys(json, setOf("action", "answer"))) return invalid("unknown_arguments")
                val answer = json.opt("answer") as? String ?: return invalid("missing_answer")
                if (answer.isBlank() || answer.length > MAX_ANSWER_CHARS || answer.any(Char::isISOControl)) {
                    return invalid("invalid_answer")
                }
                SearchToolParseResult(SearchToolAction.Final(answer.trim()))
            }
            "NO_MORE_SEARCH" -> if (hasOnlyKeys(json, setOf("action"))) {
                SearchToolParseResult(SearchToolAction.NoMoreSearch)
            } else invalid("unknown_arguments")
            "UNSUPPORTED" -> if (hasOnlyKeys(json, setOf("action"))) {
                SearchToolParseResult(SearchToolAction.Unsupported)
            } else invalid("unknown_arguments")
            "INVALID_TOOL_CALL" -> if (hasOnlyKeys(json, setOf("action"))) {
                SearchToolParseResult(SearchToolAction.InvalidToolCall)
            } else invalid("unknown_arguments")
            else -> invalid("unknown_action")
        }
    }

    private fun parseQueries(raw: Any?): SearchToolParseResult {
        val values = raw as? JSONArray ?: return invalid("queries_not_array")
        if (values.length() !in 1..MAX_QUERIES_PER_ACTION) return invalid("query_count")
        val queries = ArrayList<String>(values.length())
        for (index in 0 until values.length()) {
            val query = values.opt(index) as? String ?: return invalid("query_not_string")
            val normalized = query.trim()
            if (normalized.isBlank() || normalized.length > MAX_QUERY_CHARS || normalized.any(Char::isISOControl)) {
                return invalid("invalid_query")
            }
            if (normalized.none(Char::isLetterOrDigit)) return invalid("query_without_terms")
            queries += normalized
        }
        return SearchToolParseResult(SearchToolAction.Search(queries))
    }

    private fun hasOnlyKeys(json: JSONObject, allowed: Set<String>): Boolean =
        json.keys().asSequence().all(allowed::contains)

    private fun invalid(code: String) = SearchToolParseResult(SearchToolAction.InvalidToolCall, code)
}

/** Small strict RFC 8259 syntax gate before Android's more permissive org.json parser. */
private object StrictJsonSyntax {
    fun isValid(raw: String): Boolean = runNonFatal { Parser(raw).parse() } ?: false

    private class Parser(private val input: String) {
        private var at = 0

        fun parse(): Boolean {
            whitespace()
            if (!value(0)) return false
            whitespace()
            return at == input.length
        }

        private fun value(depth: Int): Boolean {
            if (depth > 8 || at >= input.length) return false
            return when (input[at]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> numberValue()
                else -> false
            }
        }

        private fun objectValue(depth: Int): Boolean {
            at++
            whitespace()
            if (consume('}')) return true
            while (true) {
                if (!stringValue()) return false
                whitespace()
                if (!consume(':')) return false
                whitespace()
                if (!value(depth)) return false
                whitespace()
                if (consume('}')) return true
                if (!consume(',')) return false
                whitespace()
                if (at >= input.length || input[at] == '}') return false
            }
        }

        private fun arrayValue(depth: Int): Boolean {
            at++
            whitespace()
            if (consume(']')) return true
            while (true) {
                if (!value(depth)) return false
                whitespace()
                if (consume(']')) return true
                if (!consume(',')) return false
                whitespace()
                if (at >= input.length || input[at] == ']') return false
            }
        }

        private fun stringValue(): Boolean {
            if (!consume('"')) return false
            while (at < input.length) {
                val char = input[at++]
                when {
                    char == '"' -> return true
                    char == '\\' -> {
                        if (at >= input.length) return false
                        when (input[at++]) {
                            '"', '\\', '/' -> Unit
                            'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> {
                                if (at + 4 > input.length || input.substring(at, at + 4).any { it.digitToIntOrNull(16) == null }) return false
                                at += 4
                            }
                            else -> return false
                        }
                    }
                    char.code < 0x20 -> return false
                }
            }
            return false
        }

        private fun numberValue(): Boolean {
            val start = at
            consume('-')
            if (consume('0')) {
                if (at < input.length && input[at].isDigit()) return false
            } else {
                if (!digits(required = true)) return false
            }
            if (consume('.')) if (!digits(required = true)) return false
            if (at < input.length && input[at] in "eE") {
                at++
                if (at < input.length && input[at] in "+-") at++
                if (!digits(required = true)) return false
            }
            return at > start
        }

        private fun digits(required: Boolean): Boolean {
            val start = at
            while (at < input.length && input[at] in '0'..'9') at++
            return !required || at > start
        }

        private fun literal(value: String): Boolean {
            if (!input.startsWith(value, at)) return false
            at += value.length
            return true
        }

        private fun whitespace() {
            while (at < input.length && input[at] in setOf(' ', '\t', '\r', '\n')) at++
        }

        private fun consume(char: Char): Boolean {
            if (at >= input.length || input[at] != char) return false
            at++
            return true
        }
    }
}

/** Explicitly conservative: only the official OpenAI HTTPS endpoint is native-tool enabled. */
object ExternalSearchToolCallingSupport {
    fun supports(providerType: com.projectnuke.fusion.ai.model.AiProviderType, baseUrl: String): Boolean {
        if (providerType != com.projectnuke.fusion.ai.model.AiProviderType.OPENAI) return false
        val uri = runNonFatal { java.net.URI(baseUrl.trim()) } ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("api.openai.com", ignoreCase = true) &&
            (uri.path == "/v1" || uri.path.startsWith("/v1/"))
    }
}
