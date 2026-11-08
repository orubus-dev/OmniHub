package com.omnihub.soul

import android.content.Context
import android.util.Log
import com.omnihub.providers.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SoulManager(context: Context) {
    private val TAG = "SoulManager"
    private val root = File(context.filesDir, "Omni/memory").also { it.mkdirs() }
    private val soulFile = File(root, "soul.md")
    private val unitsFile = File(root, "units.json")
    private val soulUnits = mutableListOf<SoulUnit>()

    init { load() }

    fun setStoragePath(path: String) {
        Log.d(TAG, "Custom storage path requested: $path")
    }

    private fun isJunk(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("relayed to") ||
            t.contains("streaming back") ||
            t.contains("your message was delivered") ||
            t.contains("sign in to") ||
            t.contains("open providers") ||
            t.contains("session ok") ||
            t.contains("install the") ||
            t.contains("api key") ||
            t.contains("cookie") ||
            t.startsWith("[") && t.contains("] relayed")
    }

    /**
     * v1.3.0 — memory distills from the USER's turn ONLY.
     *
     * The old version stored `assistantReply.take(400)` as the unit body and
     * `assistantReply.take(200)` as the summary. A creative reply (a story, a
     * roleplay continuation) thus became "memory" and was injected into every
     * later conversation on every provider — which is exactly how the Kai &
     * Lena story started answering unrelated questions across three
     * providers. Structural fix at the root: what the USER says is memory;
     * what the AI writes is conversation, never memory. The thread itself
     * still rides with the request (transcript continuity), so nothing is
     * lost — it just never pollutes the durable profile.
     */
    suspend fun learnFromExchange(
        sourceId: String,
        messages: List<ChatMessage>,
        assistantReply: String,
        conversationId: String?
    ) = withContext(Dispatchers.Default) {
        val userText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (userText.isBlank()) return@withContext
        if (isJunk(userText)) return@withContext
        // ToolLoop protocol traffic (tool results / reminders) is app plumbing,
        // never durable memory about the user.
        if (userText.trimStart().startsWith("[TOOL_RESULT")) return@withContext
        val convId = conversationId ?: UUID.randomUUID().toString()
        val topic = extractTopic(userText)
        val distilled = distillUserTurn(userText)
        if (distilled.isBlank()) return@withContext
        val unit = SoulUnit(
            id = "soul-${UUID.randomUUID()}",
            type = SoulType.CONVERSATION,
            content = "User said: $distilled",
            sourceId = sourceId,
            conversationId = convId,
            topic = topic,
            summary = distilled.take(200),
            keyFacts = extractFacts(userText),
            tags = extractTags(userText),
            importance = 0.7,
            policy = SoulPolicy.LONG_TERM
        )
        synchronized(soulUnits) {
            soulUnits.add(unit)
            if (soulUnits.size > 200) {
                soulUnits.sortByDescending { it.importance * it.createdAt }
                while (soulUnits.size > 150) soulUnits.removeAt(soulUnits.lastIndex)
            }
            persist()
        }
    }

    /** The user's ask, compacted: first sentences, hard-capped. No reply prose.
     *  ToolLoop manifest (appended after the — — — delimiter) is protocol,
     *  not the user's words — cut there first. */
    private fun distillUserTurn(userText: String): String {
        val body = userText.substringBefore("\n— — —")
        val clean = body.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= 200) return clean
        val cut = clean.take(200)
        val sentenceEnd = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("? "), cut.lastIndexOf("! "))
        return if (sentenceEnd > 80) cut.take(sentenceEnd + 1) else "$cut…"
    }

    suspend fun learnFromConversation(
        sourceId: String,
        request: com.omnihub.providers.ChatRequest,
        response: com.omnihub.providers.ChatResponse
    ) {
        learnFromExchange(sourceId, request.messages, response.content, null)
    }

    suspend fun generatePromptContext(maxUnits: Int = 5): String = withContext(Dispatchers.Default) {
        synchronized(soulUnits) {
            val recent = soulUnits
                .filter { !isJunk(it.summary) && !isJunk(it.content) }
                .sortedByDescending { it.importance * it.createdAt }
                .take(maxUnits)
            if (recent.isEmpty()) return@withContext ""
            // Hard cap: memory is background seasoning, never a second prompt.
            var total = 0
            val lines = mutableListOf<String>()
            for (u in recent) {
                val line = "- ${u.topic.ifBlank { u.summary.take(40) }}: ${u.summary.take(120)}"
                if (total + line.length > 700) break
                lines.add(line)
                total += line.length
            }
            if (lines.isEmpty()) return@withContext ""
            buildString {
                append("## Omni memory (background facts about the user; not part of the current request)\n")
                lines.forEach { append("$it\n") }
            }
        }
    }

    fun unitCount(): Int = synchronized(soulUnits) { soulUnits.size }

    /**
     * Explicit memory written by the assistant through the memory_save tool
     * (or the tunnel). Unlike learnFromExchange this stores what was ASKED to
     * be remembered, verbatim, at high importance so it surfaces in every
     * later prompt context.
     */
    suspend fun rememberExplicit(text: String, topic: String = "fact") = withContext(Dispatchers.Default) {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank() || isJunk(clean)) return@withContext
        val unit = SoulUnit(
            id = "soul-${UUID.randomUUID()}",
            type = SoulType.FACT,
            content = clean,
            sourceId = "assistant",
            conversationId = "",
            topic = topic.take(60),
            summary = clean.take(200),
            keyFacts = listOf(clean.take(160)),
            tags = listOf("explicit"),
            importance = 1.2,
            policy = SoulPolicy.LONG_TERM
        )
        synchronized(soulUnits) {
            soulUnits.add(unit)
            persist()
        }
    }

    fun loadUnits(): List<SoulUnit> = synchronized(soulUnits) {
        soulUnits.filter { !isJunk(it.summary) && !isJunk(it.content) }
    }

    private fun load() {
        try {
            if (unitsFile.exists()) {
                val arr = JSONArray(unitsFile.readText())
                var droppedLegacy = false
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val summary = o.optString("summary")
                    val content = o.optString("content")
                    // Pre-1.3.0 units embedded assistant prose ("User: …\nAssistant: …")
                    // — the story-pollution format. One-time structural migration:
                    // drop every unit of the old shape.
                    if (content.contains("\nAssistant: ") || content.startsWith("User: ")) {
                        droppedLegacy = true
                        continue
                    }
                    if (isJunk(summary) || isJunk(content)) continue
                    soulUnits.add(
                        SoulUnit(
                            id = o.optString("id"),
                            type = runCatching { SoulType.valueOf(o.optString("type", "CONVERSATION")) }.getOrDefault(SoulType.CONVERSATION),
                            content = content,
                            sourceId = o.optString("sourceId"),
                            conversationId = o.optString("conversationId"),
                            topic = o.optString("topic"),
                            summary = summary,
                            importance = o.optDouble("importance", 0.5),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                        )
                    )
                }
                // Rewrite clean file if we dropped junk or legacy units
                if (droppedLegacy) persist()
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            soulUnits.filter { !isJunk(it.summary) }.forEach { u ->
                arr.put(
                    JSONObject()
                        .put("id", u.id)
                        .put("type", u.type.name)
                        .put("content", u.content)
                        .put("sourceId", u.sourceId)
                        .put("conversationId", u.conversationId)
                        .put("topic", u.topic)
                        .put("summary", u.summary)
                        .put("importance", u.importance)
                        .put("createdAt", u.createdAt)
                        .put("tags", JSONArray(u.tags))
                )
            }
            unitsFile.writeText(arr.toString())
            val md = buildString {
                append("# Omni Soul\n\n")
                soulUnits.filter { !isJunk(it.summary) }.sortedByDescending { it.createdAt }.take(40).forEach { u ->
                    append("## ${u.topic.ifBlank { u.type.name }}\n")
                    append("- ${u.summary}\n\n")
                }
            }
            soulFile.writeText(md)
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }

    private fun extractTopic(text: String): String {
        val t = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return t.take(60).ifBlank { "Conversation" }
    }

    private fun extractFacts(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.length in 20..160 && !isJunk(it) }.take(5)

    private fun extractTags(text: String): List<String> {
        val keywords = listOf("android", "kotlin", "omnihub", "code", "design", "source", "memory")
        val lower = text.lowercase()
        return keywords.filter { it in lower }.take(6)
    }
}
