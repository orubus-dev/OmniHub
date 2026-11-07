package com.omnihub.source

import com.omnihub.OmniHubApp
import com.omnihub.providers.ChatMessage
import com.omnihub.providers.ChatResponse
import com.omnihub.soul.SoulManager
import com.omnihub.source.core.ProviderCooldown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID

class SourceRouter(
    private val sourceManager: SourceManager,
    private val soul: SoulManager,
    private val issueReporter: AutoIssueReporter
) {
    data class RouteResult(
        val response: ChatResponse,
        val sourceId: String,
        val sourceName: String
    )

    sealed class RouteEvent {
        /** Cumulative reply text so far, from the source that is currently answering. */
        data class Delta(val text: String, val sourceId: String, val sourceName: String) : RouteEvent()
        data class Done(
            val content: String,
            val sourceId: String,
            val sourceName: String,
            val elapsedMs: Long
        ) : RouteEvent()
    }

    data class TaskHints(
        val coding: Boolean = false,
        val research: Boolean = false,
        val vision: Boolean = false
    ) {
        companion object {
            fun fromPrompt(text: String): TaskHints {
                val t = text.lowercase()
                return TaskHints(
                    coding = listOf("code", "kotlin", "python", "sql", "build", "compile", "debug").any { it in t },
                    research = listOf("search", "research", "find", "lookup", "news").any { it in t },
                    vision = listOf("image", "screenshot", "photo", "vision").any { it in t }
                )
            }
        }
    }

    /** Hard cap on providers tried in one send — bounds the worst case instead
     *  of letting a row of dead providers eat minutes. */
    private val maxCandidates = 4

    suspend fun chat(
        messages: List<ChatMessage>,
        preferredSourceId: String? = null,
        conversationId: String? = null,
        taskHints: TaskHints = TaskHints()
    ): RouteResult = withContext(Dispatchers.IO) {
        val memory = soul.generatePromptContext(maxUnits = 8)
        val candidates = rank(preferredSourceId, taskHints).take(maxCandidates)
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "No providers ready. Open Store and install a Web or MCP source."
            )
        }

        val analytics = try { OmniHubApp.instance.analytics } catch (_: Exception) { null }
        val requestId = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        val userText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        analytics?.recordUserMessage(conversationId, userText)

        var last: Exception? = null
        for (src in candidates) {
            if (isCooling(src)) { last = IllegalStateException("${src.info.name} is cooling down"); continue }
            analytics?.recordRequestStart(requestId, conversationId, src.info.id, null)
            try {
                val resp = src.chat(
                    SourceChatRequest(
                        messages = messages,
                        model = src.defaultModel,
                        memoryContext = memory.ifBlank { null },
                        conversationId = conversationId
                    )
                )
                val duration = System.currentTimeMillis() - started
                val tokens = resp.usageTokens
                val estimated = tokens <= 0
                val total = if (tokens > 0) tokens else estimateTokens(userText, resp.content)
                analytics?.recordRequestResult(
                    requestId = requestId,
                    conversationId = conversationId,
                    sourceId = src.info.id,
                    modelId = resp.model,
                    durationMs = duration,
                    success = true,
                    timedOut = false,
                    inputTokens = if (estimated) total / 3 else 0,
                    outputTokens = if (estimated) total - total / 3 else tokens,
                    totalTokens = total,
                    tokensEstimated = estimated
                )
                soul.learnFromExchange(src.info.id, messages, resp.content, conversationId)
                return@withContext RouteResult(resp, src.info.id, src.info.name)
            } catch (e: Exception) {
                last = e
                val duration = System.currentTimeMillis() - started
                val timedOut = e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true
                analytics?.recordRequestResult(
                    requestId = requestId,
                    conversationId = conversationId,
                    sourceId = src.info.id,
                    modelId = null,
                    durationMs = duration,
                    success = false,
                    timedOut = timedOut,
                    inputTokens = 0,
                    outputTokens = 0,
                    totalTokens = 0,
                    tokensEstimated = true
                )
                issueReporter.reportFailure(src.info.id, src.info.name, e.message ?: e.toString())
            }
        }
        throw last ?: IllegalStateException("All sources failed")
    }

    /**
     * Streaming router — the one both the chat UI and the local gateway use.
     * Emits cumulative Deltas while a source writes, Done when it finishes,
     * and falls through to the next ranked source on failure (bounded).
     */
    fun chatStream(
        messages: List<ChatMessage>,
        preferredSourceId: String? = null,
        conversationId: String? = null,
        taskHints: TaskHints = TaskHints()
    ): Flow<RouteEvent> = flow {
        val memory = soul.generatePromptContext(maxUnits = 8)
        val candidates = rank(preferredSourceId, taskHints).take(maxCandidates)
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "No providers ready. Open Store and install a source, then sign in."
            )
        }
        val analytics = try { OmniHubApp.instance.analytics } catch (_: Exception) { null }
        val requestId = UUID.randomUUID().toString()
        val userText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        analytics?.recordUserMessage(conversationId, userText)

        var last: Exception? = null
        for (src in candidates) {
            if (isCooling(src)) { last = IllegalStateException("${src.info.name} is cooling down"); continue }
            val started = System.currentTimeMillis()
            analytics?.recordRequestStart(requestId, conversationId, src.info.id, null)
            try {
                val req = SourceChatRequest(
                    messages = messages,
                    model = src.defaultModel,
                    memoryContext = memory.ifBlank { null },
                    conversationId = conversationId
                )
                var acc = ""
                var emittedAny = false
                src.stream(req).collect { piece ->
                    if (piece.isBlank()) return@collect
                    if (piece.length >= acc.length && piece != acc) {
                        acc = piece
                        emittedAny = true
                        emit(RouteEvent.Delta(piece, src.info.id, src.info.name))
                    } else if (piece.length < acc.length && piece.isNotBlank()) {
                        // Sanitizer shortened a snapshot — accept the fresher one
                        acc = piece
                        emit(RouteEvent.Delta(piece, src.info.id, src.info.name))
                    }
                }
                val finalText = if (acc.isBlank()) throw IllegalStateException("${src.info.name}: empty reply") else acc
                val duration = System.currentTimeMillis() - started
                analytics?.recordRequestResult(
                    requestId = requestId,
                    conversationId = conversationId,
                    sourceId = src.info.id,
                    modelId = src.defaultModel.ifBlank { null },
                    durationMs = duration,
                    success = true,
                    timedOut = false,
                    inputTokens = 0,
                    outputTokens = 0,
                    totalTokens = estimateTokens(userText, finalText),
                    tokensEstimated = true
                )
                soul.learnFromExchange(src.info.id, messages, finalText, conversationId)
                emit(RouteEvent.Done(finalText, src.info.id, src.info.name, duration))
                return@flow
            } catch (e: Exception) {
                last = e
                val duration = System.currentTimeMillis() - started
                val timedOut = e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true
                analytics?.recordRequestResult(
                    requestId = requestId,
                    conversationId = conversationId,
                    sourceId = src.info.id,
                    modelId = null,
                    durationMs = duration,
                    success = false,
                    timedOut = timedOut,
                    inputTokens = 0,
                    outputTokens = 0,
                    totalTokens = 0,
                    tokensEstimated = true
                )
                issueReporter.reportFailure(src.info.id, src.info.name, e.message ?: e.toString())
            }
        }
        throw last ?: IllegalStateException("All sources failed")
    }.flowOn(Dispatchers.IO)

    private fun isCooling(src: AiSource): Boolean = try {
        ProviderCooldown.isCooling(OmniHubApp.instance, src.info.id)
    } catch (_: Exception) {
        false
    }

    private fun rank(preferred: String?, hints: TaskHints): List<AiSource> {
        val all = sourceManager.all()
        val preferredSrc = preferred?.let { id -> all.find { it.info.id == id } }
        val rest = all.filter { it.info.id != preferred }
            .filter { it.info.kind != SourceKind.MCP } // MCP servers are tools, not chat lanes
            .sortedByDescending { score(it, hints) }
        return listOfNotNull(preferredSrc) + rest
    }

    private fun score(src: AiSource, hints: TaskHints): Int {
        var s = 0
        if (src.isConfigured()) s += 10
        if (src.info.kind == SourceKind.WEB_SESSION || src.info.kind == SourceKind.WEB) s += 5
        if (hints.coding && src.info.capabilities.coding) s += 4
        if (hints.research && src.info.capabilities.research) s += 4
        return s
    }

    private fun estimateTokens(user: String, reply: String): Int =
        ((user.length + reply.length) / 4).coerceAtLeast(1)
}
