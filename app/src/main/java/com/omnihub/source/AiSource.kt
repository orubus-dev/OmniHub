package com.omnihub.source

import com.omnihub.providers.ChatMessage
import com.omnihub.providers.ChatResponse
import com.omnihub.providers.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class SourceKind {
    API, WEB, WEB_SESSION, MCP, APP, TOOL, AGENT, SKILL, HYBRID, LOCAL, EXTENSION
}

enum class AuthType {
    API_KEY, WEB_SESSION, OAUTH2, USERNAME_PASSWORD, NONE
}

enum class SourceHealth {
    HEALTHY, DEGRADED, AUTH_REQUIRED, UPDATE_REQUIRED, INCOMPATIBLE, OFFLINE, UNKNOWN
}

enum class UpdatePolicy {
    STARTUP, HOURLY, DAILY, WEEKLY, MONTHLY, MANUAL, NEVER
}

data class SourceCapabilities(
    val chat: Boolean = true,
    val stream: Boolean = false,
    val vision: Boolean = false,
    val tools: Boolean = false,
    val research: Boolean = false,
    val coding: Boolean = false,
    val multimodal: Boolean = false
)

data class SourceInfo(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val authType: AuthType,
    val description: String = "",
    val websiteUrl: String = "",
    val revision: String = "1.0.0",
    val publisher: String = "OmniHub",
    val category: String = "ai",
    val bundled: Boolean = false,
    val minOmniHubVersion: String = "1.0.2",
    val updatePolicy: UpdatePolicy = UpdatePolicy.MANUAL,
    val updateEndpoint: String? = null,
    val capabilities: SourceCapabilities = SourceCapabilities()
)

data class SourceConfig(
    val apiKey: String? = null,
    val sessionCookie: String? = null,
    val extra: Map<String, String> = emptyMap()
)

data class SourceChatRequest(
    val messages: List<ChatMessage>,
    val model: String? = null,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val memoryContext: String? = null,
    val conversationId: String? = null
)

interface AiSource {
    val info: SourceInfo
    val models: List<ModelInfo>
        get() = emptyList()
    val defaultModel: String
        get() = models.firstOrNull()?.id ?: ""

    fun isConfigured(): Boolean
    fun health(): SourceHealth =
        when {
            !isConfigured() && info.authType != AuthType.NONE -> SourceHealth.AUTH_REQUIRED
            else -> SourceHealth.HEALTHY
        }

    suspend fun configure(config: SourceConfig) {}
    suspend fun chat(request: SourceChatRequest): ChatResponse

    /**
     * Streaming lane. Emits CUMULATIVE reply snapshots (not diffs) so every
     * consumer — chat UI, local gateway, router — just replaces what it has.
     * Default: one full snapshot after chat() completes.
     */
    fun stream(request: SourceChatRequest): Flow<String> = flow {
        emit(chat(request).content)
    }
    suspend fun validateCredentials(): Boolean = isConfigured()
    suspend fun onInstall() {}
    suspend fun onUninstall() {}
}

data class SourceDescriptor(
    val id: String,
    val name: String,
    val kind: String = "API",
    val authType: String = "API_KEY",
    val version: Int = 1,
    val revision: String = "1.0.0",
    val description: String = "",
    val websiteUrl: String = "",
    val baseUrl: String? = null,
    val chatPath: String? = null,
    val models: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val apkUrl: String? = null,
    val nsfw: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val updatePolicy: String = "manual"
)
