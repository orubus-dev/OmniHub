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
import com.omnihub.source.core.WebViewChatEngine

/**
 * Duck.ai as a first-class source — the OmniHub way.
 *
 * No API keys, no challenge forging, no headless solver. The duck.ai page
 * runs in a real, warm WebView (WarmSessionPool) — its own browser runtime
 * generates every fe-signal legitimately, exactly like the user's browser
 * would. The relay engine types the prompt into the composer and reads the
 * streamed reply back. Same trick as orubus-ai's route-d, minus the
 * infrastructure: the browser IS the solver.
 *
 * Free models behind duck.ai (discovered live by orubus-ai):
 *   gpt-5.4-mini, gpt-6-luna, claude-haiku-4-5, tinfoil/gpt-oss-120b.
 * Model choice v1 = whatever the page has selected; "duck-auto" is the
 * honest id. Model-chip clicking lands with PageMap v2.
 */
class DuckAiSource(private val context: Context) : AiSource {

    override val info = SourceInfo(
        id = "duck_ai",
        name = "Duck.ai (Free, no key)",
        kind = SourceKind.WEB_SESSION,
        authType = AuthType.NONE,
        description = "DuckDuckGo AI chat in a real WebView. Free GPT, Claude Haiku and Llama-class models, no account, no API key. The page runs its own signals — OmniHub just drives it.",
        websiteUrl = "https://duck.ai",
        revision = "1.1.0",
        category = "ai",
        bundled = true,
        updatePolicy = com.omnihub.source.UpdatePolicy.STARTUP,
        capabilities = SourceCapabilities(
            chat = true,
            stream = false,
            vision = false,
            tools = false,
            research = true
        )
    )

    override val models: List<ModelInfo> = listOf(
        ModelInfo("duck-auto", "Duck.ai — page default"),
        ModelInfo("gpt-5.4-mini", "GPT 5.4 Mini (via Duck.ai)"),
        ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5 (via Duck.ai)"),
        ModelInfo("gpt-6-luna", "GPT 6 Luna (via Duck.ai)")
    )

    override fun isConfigured(): Boolean = true // no key, no login — duck.ai is open

    override fun health(): SourceHealth = SourceHealth.HEALTHY

    override suspend fun chat(request: SourceChatRequest): ChatResponse {
        val prompt = request.messages.lastOrNull { it.role == "user" }?.content
            ?: throw IllegalStateException("no user message")

        // Memory continuity: when switching providers mid-conversation the soul
        // context is carried inside the request by SourceRouter. For direct
        // gateway calls we prepend a compact transcript so duck knows the thread.
        val promptWithThread = if (request.messages.size > 1 && request.memoryContext.isNullOrBlank()) {
            val transcript = request.messages.dropLast(1)
                .joinToString("\n") { "${if (it.role == "user") "User" else "Assistant"}: ${it.content}" }
            if (transcript.length < 8000) "Conversation so far:\n$transcript\n\nUser: $prompt" else prompt
        } else prompt

        val result = WebViewChatEngine.send(
            context = context,
            siteUrl = "https://duck.ai",
            providerId = "duckduckgo_ai",
            userMessage = promptWithThread,
            timeoutMs = 75_000
        )
        if (!result.ok) throw IllegalStateException(result.text.ifBlank { "duck.ai relay failed" })
        val text = result.text.trim()
        if (text.isEmpty()) throw IllegalStateException("duck.ai returned an empty reply")
        return ChatResponse(
            content = text,
            model = request.model?.ifBlank { "duck-auto" } ?: "duck-auto",
            providerId = info.id
        )
    }
}
