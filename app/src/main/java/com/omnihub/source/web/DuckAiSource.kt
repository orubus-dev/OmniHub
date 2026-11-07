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
import com.omnihub.source.core.ProviderBridge
import com.omnihub.source.core.WebViewChatEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

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
        revision = "1.2.0",
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
        ModelInfo("gpt-6-luna", "GPT 6 Luna (via Duck.ai)")
    )

    override fun isConfigured(): Boolean = true // no key, no login — duck.ai is open

    override fun health(): SourceHealth = SourceHealth.HEALTHY

    private fun promptOf(request: SourceChatRequest): Pair<String, String> {
        val prompt = request.messages.lastOrNull { it.role == "user" }?.content
            ?: throw IllegalStateException("no user message")
        // Memory continuity on provider switch: the transcript rides as
        // contextPrefix — the engine types it in ONLY on a cold session, so a
        // warm duck.ai thread is never duplicated.
        val prefix = when {
            request.messages.size > 1 -> ProviderBridge.contextPrefixFor(request.messages, request.memoryContext)
            !request.memoryContext.isNullOrBlank() ->
                "Context about the user and this conversation (memory):\n${request.memoryContext}"
            else -> ""
        }
        return prompt to prefix
    }

    override suspend fun chat(request: SourceChatRequest): ChatResponse {
        val (prompt, prefix) = promptOf(request)
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
            model = request.model?.ifBlank { "duck-auto" } ?: "duck-auto",
            providerId = info.id
        )
    }

    /** True streaming: cumulative node text flows out as the page writes it. */
    override fun stream(request: SourceChatRequest): Flow<String> = channelFlow {
        val (prompt, prefix) = promptOf(request)
        val job = launch(Dispatchers.IO) {
            try {
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
