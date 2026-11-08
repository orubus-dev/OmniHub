package com.omnihub.source.web

import android.content.Context
import com.omnihub.providers.ChatResponse
import com.omnihub.providers.ModelInfo
import com.omnihub.source.AiSource
import com.omnihub.source.AuthType
import com.omnihub.source.SourceCapabilities
import com.omnihub.source.SourceChatRequest
import com.omnihub.source.SourceHealth
import com.omnihub.source.SourceInfo
import com.omnihub.source.SourceKind
import com.omnihub.source.core.DuckApiEngine
import com.omnihub.source.core.ProviderBridge
import com.omnihub.source.core.WebViewChatEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Duck.ai as a first-class source — the OmniHub way, fused with orubus-ai.
 *
 * TWO lanes, best-first:
 *
 *  1. DUCK API LANE (DuckApiEngine) — the warm duck.ai WebView calls
 *     duckchat/v1/status → solves the challenge in its own real browser
 *     runtime → POSTs duckchat/v1/chat and streams the SSE off the pipe.
 *     Real multi-turn (the message array goes to the API verbatim — no
 *     context-prefix typing), real model selection, first token ~2-6s.
 *     This is what makes duck.ai a true in-app ENDPOINT for the LocalGateway.
 *
 *  2. COMPOSER RELAY (WebViewChatEngine) — the PageMap-driven fallback.
 *     If the challenge layer changes, the lane degrades to typing into the
 *     page exactly like every other provider. Never a dead end.
 *
 * No API keys, no challenge forging, no headless solver. The browser IS the
 * solver.
 */
class DuckAiSource(private val context: Context) : AiSource {

    override val info = SourceInfo(
        id = "duck_ai",
        name = "Duck.ai (Free, no key)",
        kind = SourceKind.WEB_SESSION,
        authType = AuthType.NONE,
        description = "DuckDuckGo AI chat over its own API lane in a real WebView. Free GPT, Claude Haiku and Llama-class models, no account, no API key. Falls back to the composer relay automatically.",
        websiteUrl = "https://duck.ai",
        revision = "1.3.0",
        category = "ai",
        bundled = true,
        updatePolicy = com.omnihub.source.UpdatePolicy.STARTUP,
        capabilities = SourceCapabilities(
            chat = true,
            stream = true,
            vision = false,
            tools = false,
            research = true
        )
    )

    override val models: List<ModelInfo> = listOf(
        ModelInfo("duck-auto", "Duck.ai — page default"),
        ModelInfo("gpt-5.4-mini", "GPT 5.4 Mini (via Duck.ai)"),
        ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5 (via Duck.ai)"),
        ModelInfo("gpt-6-luna", "GPT 6 Luna (via Duck.ai)"),
        ModelInfo("tinfoil/gpt-oss-120b", "GPT-OSS 120B (via Duck.ai)")
    )

    override fun isConfigured(): Boolean = true // no key, no login — duck.ai is open

    override fun health(): SourceHealth = SourceHealth.HEALTHY

    /** The full thread, role-mapped for the duck API (system rides as user context). */
    private fun apiMessages(request: SourceChatRequest): List<com.omnihub.providers.ChatMessage> {
        val memory = request.memoryContext?.takeIf { it.isNotBlank() }
        val out = mutableListOf<com.omnihub.providers.ChatMessage>()
        // Memory rides ONLY on a fresh thread, clearly labeled as background —
        // never as part of the request itself.
        if (memory != null && request.messages.count { it.role == "user" } <= 1) {
            out.add(
                com.omnihub.providers.ChatMessage(
                    role = "user",
                    content = "(Background about the user, not a request — keep it in mind silently):\n$memory"
                )
            )
            out.add(
                com.omnihub.providers.ChatMessage(
                    role = "assistant",
                    content = "Understood, I'll keep that in mind."
                )
            )
        }
        for (m in request.messages) {
            if (m.content.isBlank()) continue
            out.add(
                com.omnihub.providers.ChatMessage(
                    role = if (m.role == "assistant") "assistant" else "user",
                    content = m.content
                )
            )
        }
        return out
    }

    /** Composer-relay fallback: last user turn + compact prefix (legacy path). */
    private fun relayMessages(request: SourceChatRequest): Pair<String, String> {
        val prompt = request.messages.lastOrNull { it.role == "user" }?.content
            ?: throw IllegalStateException("no user message")
        val prefix = when {
            request.messages.size > 1 -> ProviderBridge.contextPrefixFor(request.messages, null)
            !request.memoryContext.isNullOrBlank() ->
                "Context about the user and this conversation (memory):\n${request.memoryContext}"
            else -> ""
        }
        return prompt to prefix
    }

    override suspend fun chat(request: SourceChatRequest): ChatResponse {
        val model = request.model?.ifBlank { "duck-auto" } ?: "duck-auto"
        // Lane 1: real API
        val api = DuckApiEngine.send(
            context = context,
            model = model,
            messages = apiMessages(request)
        )
        if (api.ok && api.text.isNotBlank()) {
            return ChatResponse(
                content = api.text.trim(),
                model = model,
                providerId = info.id
            )
        }
        // Lane 2: composer relay fallback
        val (prompt, prefix) = relayMessages(request)
        val result = WebViewChatEngine.send(
            context = context,
            siteUrl = "https://duck.ai",
            providerId = "duckduckgo_ai",
            userMessage = prompt,
            timeoutMs = 75_000,
            contextPrefix = prefix
        )
        if (!result.ok) throw IllegalStateException(result.text.ifBlank { "duck.ai relay failed" })
        val text = result.text.trim()
        if (text.isEmpty()) throw IllegalStateException("duck.ai returned an empty reply")
        return ChatResponse(
            content = text,
            model = model,
            providerId = info.id
        )
    }

    /** True streaming: API SSE deltas flow out live; composer relay is fallback. */
    override fun stream(request: SourceChatRequest): Flow<String> = channelFlow {
        val model = request.model?.ifBlank { "duck-auto" } ?: "duck-auto"
        val job = launch(Dispatchers.IO) {
            try {
                val api = DuckApiEngine.send(
                    context = context,
                    model = model,
                    messages = apiMessages(request),
                    onPartial = { p -> trySend(p) }
                )
                if (api.ok && api.text.isNotBlank()) {
                    trySend(api.text.trim())
                    close()
                    return@launch
                }
                // Fallback lane: composer relay
                val (prompt, prefix) = relayMessages(request)
                val result = WebViewChatEngine.send(
                    context = context,
                    siteUrl = "https://duck.ai",
                    providerId = "duckduckgo_ai",
                    userMessage = prompt,
                    timeoutMs = 75_000,
                    contextPrefix = prefix,
                    onPartial = { p -> trySend(p) }
                )
                if (!result.ok) throw IllegalStateException(result.text.ifBlank { "duck.ai relay failed" })
                trySend(result.text.trim())
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)
}
