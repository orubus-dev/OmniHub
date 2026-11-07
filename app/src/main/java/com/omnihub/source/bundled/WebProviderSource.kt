package com.omnihub.source.bundled

import android.content.Context
import com.omnihub.providers.ChatResponse
import com.omnihub.source.*
import com.omnihub.source.core.ProviderAuthStore
import com.omnihub.source.core.ProviderBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class WebProviderSource(
    private val context: Context,
    private val sourceId: String,
    private val sourceName: String,
    private val siteUrl: String,
    private val description: String = "",
    private val kind: SourceKind = SourceKind.WEB
) : AiSource {
    override val info = SourceInfo(
        id = sourceId,
        name = sourceName,
        kind = kind,
        authType = AuthType.NONE,
        description = description,
        websiteUrl = siteUrl,
        bundled = true,
        capabilities = SourceCapabilities(chat = true, stream = true, coding = true, research = true)
    )

    // Always attempt; bridge decides guest vs session
    override fun isConfigured(): Boolean = true

    override fun health(): SourceHealth =
        if (ProviderAuthStore.isSignedIn(context, sourceId)) SourceHealth.HEALTHY
        else SourceHealth.UNKNOWN

    override suspend fun chat(request: SourceChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        ProviderBridge.chatOnce(
            context = context,
            providerId = sourceId,
            providerName = sourceName,
            siteUrl = siteUrl,
            messages = request.messages,
            kind = if (kind == SourceKind.MCP) "MCP" else "WEB"
        )
    }

    /** True streaming: partial page text flows out while the provider writes. */
    override fun stream(request: SourceChatRequest): Flow<String> = flow {
        var finalText = ""
        var finalOk = false
        ProviderBridge.streamChat(
            context = context,
            providerId = sourceId,
            providerName = sourceName,
            siteUrl = siteUrl,
            messages = request.messages,
            kind = if (kind == SourceKind.MCP) "MCP" else "WEB"
        ).collect { tok ->
            if (tok.done) {
                finalText = tok.text
                finalOk = tok.ok
            } else if (tok.text.isNotBlank()) {
                emit(tok.text)
            }
        }
        if (!finalOk) throw IllegalStateException(finalText.ifBlank { "$sourceName relay failed" })
        emit(finalText)
    }.flowOn(Dispatchers.IO)
}
