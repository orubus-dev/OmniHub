package com.omnihub.tools

import com.omnihub.providers.ChatMessage
import com.omnihub.source.SourceRouter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

/**
 * ToolLoopEngine — real tool use for every source, including pure web relays.
 *
 * Web AIs (ChatGPT web, duck.ai, Gemini web…) have no native function calling
 * we can drive, so the loop runs CLIENT-side:
 *
 *   1. The user's message is annotated with a compact tool manifest.
 *   2. The model answers. If the answer contains an ```omnitool fenced JSON
 *      block, that block is parsed (brace-matched, tolerant of prose and of
 *      broken JSON), stripped from what the user sees, and executed through
 *      the ToolHost (builtins + all connected MCP servers).
 *   3. The tool result is fed back as a [TOOL_RESULT] message that also
 *      re-states the protocol in one line (web relays only see the last
 *      message, so the reminder must travel with the result).
 *   4. Repeat until the model answers with plain text, or maxIterations.
 *
 * Streaming is preserved end-to-end: the model's interim text streams out
 * with the tool block masked, so the UI never flashes raw JSON.
 */
class ToolLoopEngine(
    private val router: SourceRouter,
    private val toolHost: ToolHost
) {

    companion object {
        const val MAX_ITERATIONS = 6
        const val FENCE = "omnitool"
        const val RESULT_TAG_OPEN = "[TOOL_RESULT"
        const val RESULT_TAG_CLOSE = "[/TOOL_RESULT]"
    }

    sealed class LoopEvent {
        /** Cumulative visible reply text from the current source (tool syntax masked). */
        data class Delta(val text: String, val sourceId: String, val sourceName: String) : LoopEvent()

        data class ToolStarted(val tool: String, val argsPreview: String) : LoopEvent()

        data class ToolFinished(val tool: String, val ok: Boolean, val outputPreview: String) : LoopEvent()

        data class Done(
            val content: String,
            val sourceId: String,
            val sourceName: String,
            val elapsedMs: Long,
            val toolCalls: Int
        ) : LoopEvent()
    }

    // ---------- manifest ----------

    /**
     * Compact two-tier manifest. Full schemas would bloat every web-relay
     * composer (typed text) as the MCP registry grows — so the first tier is
     * one bounded line per tool, and `tool_schema` fetches exact parameters
     * on demand. Hard cap keeps the payload bounded no matter how many
     * servers the user pastes.
     */
    fun buildManifest(): String {
        val tools = toolHost.allTools()
        val MAX_LISTED = 40
        val sb = StringBuilder()
        sb.append("\n\n— — —\nTOOLS: you can act through tools on this user's device.\n")
        sb.append("To call one, END your reply with exactly one fenced block and nothing after it:\n")
        sb.append("```$FENCE\n{\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n```\n")
        sb.append("Rules: one tool block per reply; no text after the block. The next user message will carry [TOOL_RESULT name=…]. ")
        sb.append("If unsure of a tool's exact parameters, call tool_schema first. ")
        sb.append("When you have the final answer, reply in plain text with NO tool block.\n")
        sb.append("Tools:\n")
        val listed = tools.take(MAX_LISTED)
        for (t in listed) {
            sb.append("- ${t.name}: ${t.description.take(80)}\n")
        }
        if (tools.size > MAX_LISTED) {
            sb.append("(+${tools.size - MAX_LISTED} more — call tool_schema on any name below to inspect)\n")
        }
        return sb.toString()
    }

    // ---------- parser ----------

    data class ToolCall(val name: String, val arguments: JSONObject, val rawStart: Int, val rawEnd: Int)

    /** Extract the first tool call from reply text. Tolerates: fences with
     *  language hints, leading prose, pretty-printed JSON, single quotes,
     *  trailing commas, and unquoted keys — in that order. */
    fun parseToolCall(text: String): ToolCall? {
        // 1) fenced block ```omnitool ... ```
        val fenceRegex = Regex("```\\s*$FENCE\\s*\\n?", RegexOption.IGNORE_CASE)
        val fenceMatch = fenceRegex.find(text)
        if (fenceMatch != null) {
            val bodyStart = fenceMatch.range.last + 1
            val closing = text.indexOf("```", bodyStart)
            val body = if (closing >= 0) text.substring(bodyStart, closing) else text.substring(bodyStart)
            val json = extractJsonObject(body)
            if (json != null) {
                val call = toCall(json, fenceMatch.range.first, if (closing >= 0) closing + 3 else text.length)
                if (call != null) return call
            }
        }
        // 2) bare JSON object containing "name" + "arguments"/"parameters" keys
        val idx = text.indexOf("\"name\"")
        if (idx >= 0) {
            val open = text.indexOf('{', idx)
            if (open >= 0) {
                val json = extractJsonObject(text.substring(open))
                if (json != null && (json.has("arguments") || json.has("parameters"))) {
                    val call = toCall(json, open, text.length)
                    if (call != null) return call
                }
            }
        }
        return null
    }

    /** Brace-matched extraction of the first top-level JSON object. */
    private fun extractJsonObject(s: String): JSONObject? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            if (inStr) {
                when (c) {
                    '\\' -> esc = true
                    '"' -> inStr = false
                }
                continue
            }
            when {
                c == '"' -> inStr = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = s.substring(start, i + 1)
                        return tryParseJson(candidate)
                    }
                }
            }
        }
        // Unbalanced (model got cut off) — try to repair by closing braces/strings
        return repairAndParse(s.substring(start))
    }

    private fun tryParseJson(candidate: String): JSONObject? = try {
        JSONObject(candidate)
    } catch (_: Exception) {
        try { JSONObject(lenientFix(candidate)) } catch (_: Exception) { null }
    }

    private fun repairAndParse(fragment: String): JSONObject? {
        var s = fragment
        // close an open string before counting braces
        if (s.count { it == '"' } % 2 == 1) s += "\""
        // count braces outside strings (approximate)
        var depth = 0; var inStr = false; var esc = false
        for (c in s) {
            if (esc) { esc = false; continue }
            if (inStr) { if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when { c == '"' -> inStr = true; c == '{' -> depth++; c == '}' -> depth-- }
        }
        s += "}".repeat(depth.coerceAtLeast(0))
        return tryParseJson(s)
    }

    /** Common model JSON mistakes: single quotes, trailing commas, unquoted keys. */
    private fun lenientFix(s: String): String {
        var t = s.replace(Regex(",\\s*([}\\]])"), "$1")          // trailing commas
        t = t.replace(Regex("(\\{|,)\\s*'([^']+)'\\s*:"), "$1\"$2\":") // unquoted/single-quoted keys
        t = t.replace(Regex(":\\s*'([^']*)'"), ": \"$1\"")        // single-quoted values
        t = t.replace(Regex("\\bTrue\\b"), "true").replace(Regex("\\bFalse\\b"), "false")
        t = t.replace(Regex("\\bNone\\b"), "null")
        return t
    }

    private fun toCall(j: JSONObject, start: Int, end: Int): ToolCall? {
        val rawName = j.optString("name", j.optString("tool", "")).trim()
        if (rawName.isBlank()) return null
        var args = j.optJSONObject("arguments") ?: j.optJSONObject("parameters") ?: JSONObject()
        // arguments may arrive as a JSON *string* — parse it
        if (args.length() == 0) {
            val asText = j.optString("arguments", j.optString("parameters", ""))
            if (asText.isNotBlank()) args = tryParseJson(asText) ?: JSONObject()
        }
        return ToolCall(rawName, args, start, end)
    }

    /** Mask tool-call syntax out of streaming text so the UI shows a clean status instead of JSON. */
    fun maskToolSyntax(text: String): String {
        val call = parseToolCall(text) ?: return text
        val prefix = text.take(call.rawStart).trimEnd()
        val suffix = text.drop(call.rawEnd).trim()
        val keep = listOf(prefix, "🔧 ${call.name}…", suffix).filter { it.isNotBlank() }
        return keep.joinToString("\n")
    }

    // ---------- the loop ----------

    fun run(
        messages: List<ChatMessage>,
        preferredSourceId: String? = null,
        conversationId: String? = null,
        taskHints: SourceRouter.TaskHints = SourceRouter.TaskHints()
    ): Flow<LoopEvent> = flow {
        val manifest = buildManifest()
        val working = messages.toMutableList()
        var totalToolCalls = 0
        val loopStart = System.currentTimeMillis()
        var lastSource = "" to ""

        // First send: annotate the LAST user message (the one web relays type).
        annotateLastUserMessage(working, manifest)

        for (iteration in 0 until MAX_ITERATIONS) {
            var acc = ""
            var sourceId = ""
            var sourceName = ""
            var elapsed = 0L

            router.chatStream(
                messages = working,
                preferredSourceId = preferredSourceId,
                conversationId = conversationId,
                taskHints = taskHints
            ).collect { ev ->
                when (ev) {
                    is SourceRouter.RouteEvent.Delta -> {
                        acc = ev.text
                        sourceId = ev.sourceId
                        sourceName = ev.sourceName
                        emit(LoopEvent.Delta(maskToolSyntax(acc), ev.sourceId, ev.sourceName))
                    }
                    is SourceRouter.RouteEvent.Done -> {
                        acc = ev.content
                        sourceId = ev.sourceId
                        sourceName = ev.sourceName
                        elapsed = ev.elapsedMs
                    }
                }
            }
            lastSource = sourceId to sourceName

            val call = parseToolCall(acc)
            if (call == null) {
                emit(
                    LoopEvent.Done(
                        content = acc.trim(),
                        sourceId = sourceId,
                        sourceName = sourceName,
                        elapsedMs = if (elapsed > 0) elapsed else System.currentTimeMillis() - loopStart,
                        toolCalls = totalToolCalls
                    )
                )
                return@flow
            }

            // Execute the tool
            totalToolCalls++
            val argsPreview = call.arguments.toString().take(120)
            emit(LoopEvent.ToolStarted(call.name, argsPreview))
            val result = toolHost.execute(call.name, call.arguments, origin = "AI")
            emit(
                LoopEvent.ToolFinished(
                    call.name,
                    result.ok,
                    result.output.take(200).replace('\n', ' ')
                )
            )

            // Feed the result back and continue the loop
            working.add(ChatMessage("assistant", acc.substring(0, minOf(acc.length, 4000))))
            working.add(ChatMessage("user", feedbackBody(call.name, result)))
        }

        // Iterations exhausted — ask for the final answer one last time
        working.add(
            ChatMessage(
                "user",
                "$RESULT_TAG_OPEN note]$RESULT_TAG_CLOSE Tool limit reached. Give your final plain-text answer now — no more tool calls."
            )
        )
        var acc = ""
        var sourceId = ""
        var sourceName = ""
        router.chatStream(
            messages = working,
            preferredSourceId = preferredSourceId,
            conversationId = conversationId,
            taskHints = taskHints
        ).collect { ev ->
            when (ev) {
                is SourceRouter.RouteEvent.Delta -> {
                    acc = ev.text
                    sourceId = ev.sourceId
                    sourceName = ev.sourceName
                    emit(LoopEvent.Delta(maskToolSyntax(acc), ev.sourceId, ev.sourceName))
                }
                is SourceRouter.RouteEvent.Done -> {
                    acc = ev.content
                    sourceId = ev.sourceId
                    sourceName = ev.sourceName
                }
            }
        }
        emit(
            LoopEvent.Done(
                content = stripToolBlocks(acc).trim(),
                sourceId = sourceId,
                sourceName = sourceName,
                elapsedMs = System.currentTimeMillis() - loopStart,
                toolCalls = totalToolCalls
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun annotateLastUserMessage(working: MutableList<ChatMessage>, manifest: String) {
        val lastUserIdx = working.indexOfLast { it.role == "user" }
        if (lastUserIdx >= 0) {
            val m = working[lastUserIdx]
            working[lastUserIdx] = m.copy(content = m.content + manifest)
        } else {
            working.add(ChatMessage("user", "TOOLS activation.${manifest}"))
        }
    }

    private fun feedbackBody(toolName: String, result: ToolHost.ToolResult): String {
        val status = if (result.ok) "ok" else "error"
        return buildString {
            append("$RESULT_TAG_OPEN name=$toolName status=$status]\n")
            append(result.output)
            append("\n$RESULT_TAG_CLOSE\n\n")
            append("Continue toward the user's goal. You may call another tool (same ```$FENCE block format) ")
            append("or reply with the final plain-text answer.")
        }
    }

    private fun stripToolBlocks(text: String): String {
        var t = text.replace(Regex("```\\s*$FENCE\\s*[\\s\\S]*?```", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("```\\s*$FENCE[\\s\\S]*$"), "")
        return t.trim()
    }
}
