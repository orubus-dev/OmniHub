package com.omnihub.source.core

import android.content.Context
import android.webkit.CookieManager
import com.omnihub.data.SecureStore
import com.omnihub.providers.ChatMessage
import com.omnihub.providers.ChatResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ProviderBridge {

    data class StreamToken(
        val text: String,
        val done: Boolean = false,
        val sourceId: String = "",
        val sourceName: String = "",
        val ok: Boolean = true
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun streamChat(
        context: Context,
        providerId: String,
        providerName: String,
        siteUrl: String,
        messages: List<ChatMessage>,
        kind: String = "WEB"
    ): Flow<StreamToken> = streamChatWithFallback(
        context,
        listOf(Triple(providerId, providerName, siteUrl)),
        messages,
        kind
    )

    /** Compact transcript of prior turns — typed in on COLD sessions only, so a
     *  provider the user just switched to still knows the thread.
     *  v1.3.0: hard-capped small (last turns, ~1600 chars total). The old 3.5k
     *  transcript made every cold send slow to type, hit composer length
     *  limits on several providers, and left visible garbage when a send
     *  failed. duck.ai doesn't need this at all anymore — its API lane takes
     *  the real message array. */
    fun contextPrefixFor(messages: List<ChatMessage>, memoryContext: String? = null): String {
        val prior = messages.dropLast(1).filter { it.content.isNotBlank() }.takeLast(2)
        val transcript = prior
            .joinToString("\n") { "${if (it.role == "user") "User" else "Assistant"}: ${it.content.take(700)}" }
            .let { if (it.length > 1600) it.takeLast(1550) else it }
        return when {
            transcript.isNotBlank() ->
                "Earlier in this conversation (you were the assistant). Continue seamlessly from the last message:\n$transcript"
            !memoryContext.isNullOrBlank() ->
                "Context about the user and this conversation (memory):\n${memoryContext.take(700)}"
            else -> ""
        }
    }

    /**
     * Live stream: emits cumulative cleaned text as the page grows (done=false),
     * then a final token (done=true). Failure of one candidate NEVER becomes a
     * reply — it is recorded and the next candidate runs (bounded by
     * [maxCandidates] so a row of dead providers can never cost minutes).
     */
    fun streamChatWithFallback(
        context: Context,
        candidates: List<Triple<String, String, String>>,
        messages: List<ChatMessage>,
        kind: String = "WEB",
        maxCandidates: Int = 4
    ): Flow<StreamToken> = callbackFlow {
        val tried = candidates.take(maxCandidates)
        if (tried.isEmpty()) {
            trySend(StreamToken("No source installed. Open Store → install → Sign in.", done = true, ok = false))
            close()
            return@callbackFlow
        }
        val last = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (last.isBlank()) {
            trySend(StreamToken("Empty message.", done = true, ok = false))
            close()
            return@callbackFlow
        }

        val job = launch(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            for ((id, name, url) in tried) {
                if (ProviderCooldown.isCooling(context, id)) {
                    errors += ProviderCooldown.message(context, id, name)
                    continue
                }
                if (kind.equals("MCP", true)) {
                    val mcp = runMcpAction(context, id, name, url, last)
                    trySend(StreamToken(mcp, done = true, sourceId = id, sourceName = name))
                    close()
                    return@launch
                }

                val host = hostOf(url.ifBlank { "https://chatgpt.com" })
                val cookies = cookieHeader(
                    context, id, host, host.removePrefix("www."),
                    "chatgpt.com", "auth.openai.com"
                )
                if (cookies.isBlank() && !ProviderAuthStore.isSignedIn(context, id)) {
                    errors += "Not signed in to $name"
                    continue
                }

                val lastPartial = AtomicReference("")
                val result = try {
                    WebViewChatEngine.send(
                        context = context,
                        siteUrl = url.ifBlank { "https://$host" },
                        providerId = id,
                        userMessage = last,
                        contextPrefix = contextPrefixFor(messages),
                        onPartial = { partial ->
                            val cleaned = ReplySanitizer.strip(partial)
                            if (cleaned.isNotBlank() && cleaned != lastPartial.get()) {
                                lastPartial.set(cleaned)
                                trySend(StreamToken(cleaned, done = false, sourceId = id, sourceName = name))
                            }
                        }
                    )
                } catch (e: Exception) {
                    WebViewChatEngine.Result(
                        e.message ?: e.javaClass.simpleName,
                        false
                    )
                }

                // A failed relay is a FAILURE — record it and fall through to the
                // next candidate. It is never shown to the user as a reply.
                if (!result.ok) {
                    errors += "$name: ${result.text.take(160)}"
                    continue
                }

                val text = ReplySanitizer.strip(result.text).ifBlank { result.text }
                if (looksBlocked(text)) {
                    ProviderCooldown.markBlocked(context, id, 15)
                    errors += "$name blocked this session. Cooling down 15m."
                    continue
                }
                if (text.startsWith("Not signed in") || text.contains("input not found", true) ||
                    text.contains("No chat input", true)
                ) {
                    errors += "$name: $text"
                    continue
                }

                trySend(StreamToken(text, done = true, sourceId = id, sourceName = name))
                close()
                return@launch
            }
            trySend(
                StreamToken(
                    "All providers failed or are cooling down.\n" +
                        errors.distinct().take(3).joinToString("\n"),
                    done = true,
                    ok = false
                )
            )
            close()
        }

        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun chatOnce(
        context: Context,
        providerId: String,
        providerName: String,
        siteUrl: String,
        messages: List<ChatMessage>,
        kind: String = "WEB"
    ): ChatResponse = withContext(Dispatchers.IO) {
        var last = ""
        streamChat(context, providerId, providerName, siteUrl, messages, kind).collect { tok ->
            if (tok.text.isNotEmpty()) last = tok.text
        }
        ChatResponse(
            content = last.ifBlank { "No reply." },
            model = providerName,
            providerId = providerId
        )
    }

    private fun looksBlocked(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("unusual activity") ||
            t.contains("session expired or blocked") ||
            (t.contains("cooling down") && t.contains("blocked"))
    }

    private fun hostOf(url: String): String =
        try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }

    private fun cookieHeader(context: Context, providerId: String, vararg hosts: String): String {
        val active = AccountStore.activeAccountId(context, providerId)
        val sessionKeys = listOf(
            AccountStore.sessionKey(providerId, active),
            providerId,
            "web_$providerId"
        )
        val stored = sessionKeys
            .mapNotNull { SecureStore.getSession(context, it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        val liveParts = linkedSetOf<String>()
        val cm = try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            null
        }
        if (cm != null) {
            for (h in hosts) {
                for (u in listOf("https://$h", "https://www.$h")) {
                    try {
                        val c = cm.getCookie(u).orEmpty()
                        if (c.isNotBlank()) {
                            c.split(";").map { it.trim() }.filter { it.contains("=") }
                                .forEach { liveParts.add(it) }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val live = liveParts.joinToString("; ")
        return when {
            stored.isNotBlank() && live.isNotBlank() -> mergeCookies(stored, live)
            stored.isNotBlank() -> stored
            else -> live
        }
    }

    private fun mergeCookies(a: String, b: String): String {
        val map = linkedMapOf<String, String>()
        fun putAll(s: String) {
            s.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                val i = it.indexOf('=')
                map[it.substring(0, i)] = it.substring(i + 1)
            }
        }
        putAll(a); putAll(b)
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun runMcpAction(
        context: Context,
        providerId: String,
        providerName: String,
        siteUrl: String,
        task: String
    ): String {
        val cookies = cookieHeader(context, providerId, hostOf(siteUrl))
        if (cookies.isBlank()) return "Sign in to $providerName first."
        return "MCP session ready on $providerName. Task: ${task.take(200)}"
    }
}

object ProviderAuthStore {
    private const val PREFS = "omni_provider_auth"

    fun isSignedIn(context: Context, providerId: String): Boolean {
        val active = AccountStore.activeAccountId(context, providerId)
        val keys = listOf(
            AccountStore.sessionKey(providerId, active),
            providerId,
            "web_$providerId"
        )
        if (keys.any { !SecureStore.getSession(context, it).isNullOrBlank() }) return true
        return context.getSharedPreferences(PREFS, 0).getBoolean("signed_$providerId", false)
    }

    fun setSignedIn(context: Context, providerId: String, signed: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("signed_$providerId", signed).apply()
        if (!signed) {
            SecureStore.clearSession(context, providerId)
            SecureStore.clearSession(context, "web_$providerId")
            val active = AccountStore.activeAccountId(context, providerId)
            if (!active.isNullOrBlank()) {
                SecureStore.clearSession(context, AccountStore.sessionKey(providerId, active))
            }
            ProviderCooldown.clear(context, providerId)
        }
    }

    fun preferredProvider(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("preferred", "auto") ?: "auto"

    fun setPreferredProvider(context: Context, id: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("preferred", id).apply()
    }
}
