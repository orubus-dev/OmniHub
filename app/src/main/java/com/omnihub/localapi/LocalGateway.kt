package com.omnihub.localapi

import com.omnihub.OmniHubApp
import com.omnihub.providers.ChatMessage
import com.omnihub.source.SourceChatRequest
import com.omnihub.source.SourceRouter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OmniHub Local Gateway — OmniRoute, on your phone.
 *
 * Turns every source the user signed into (web sessions, WebView relays, API
 * sources) into an OpenAI-compatible local endpoint. No API keys, ever:
 *
 *   POST http://127.0.0.1:8099/v1/chat/completions
 *   { "model": "auto", "messages": [...] }
 *
 * Model ids are "sourceId/modelId" (e.g. "duck_ai/gpt-5.4-mini",
 * "chatgpt_web/gpt-5"). "auto" routes through SourceRouter with soul memory
 * injected, so any OpenAI-speaking client (Termux CLI, aider, your own app,
 * another AI app on the phone) rides the signed-in web AIs.
 *
 * stream:true emits REAL incremental deltas — as the WebView page writes the
 * reply, tokens flow out of the SSE pipe; nothing waits for full completion.
 */
class LocalGateway(
    private val app: OmniHubApp,
    port: Int = DEFAULT_PORT,
    private val token: String? = null
) : NanoHTTPD(if (lanEnabled) "0.0.0.0" else "127.0.0.1", port) {

    companion object {
        const val DEFAULT_PORT = 8099
        var lanEnabled = false
        val running = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- routing ----------

    override fun serve(session: IHTTPSession): Response {
        val resp = try {
            handle(session)
        } catch (e: Exception) {
            error(Response.Status.INTERNAL_ERROR, e.message ?: "gateway error")
        }
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return resp
    }

    private fun handle(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        }
        val uri = session.uri.trimEnd('/')

        if (token != null) {
            val auth = session.headers["authorization"] ?: ""
            if (auth != "Bearer $token") return error(Response.Status.UNAUTHORIZED, "bad gateway token")
        }

        return when {
            uri == "" || uri == "/health" -> ok(
                JSONObject()
                    .put("ok", true)
                    .put("service", "omnihub-gateway")
                    .put("sources", app.sourceManager.configured().size)
                    .put("lan", lanEnabled)
            )
            uri.endsWith("/v1/models") -> models()
            uri.endsWith("/v1/chat/completions") -> chatCompletions(session)
            uri == "/mcp" -> mcpTunnel(session)
            else -> error(Response.Status.NOT_FOUND, "unknown route $uri")
        }
    }

    private fun models(): Response {
        val arr = JSONArray()
        for (src in app.sourceManager.all()) {
            for (m in src.models) {
                arr.put(
                    JSONObject()
                        .put("id", "${src.info.id}/${m.id}")
                        .put("object", "model")
                        .put("owned_by", src.info.name)
                )
            }
        }
        return ok(JSONObject().put("object", "list").put("data", arr))
    }

    private fun chatCompletions(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: throw IllegalStateException("empty body")
        val body = JSONObject(raw)

        val messages = body.optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                ChatMessage(
                    role = m.optString("role", "user"),
                    content = m.optString("content", "")
                )
            }
        } ?: throw IllegalStateException("messages required")
        if (messages.none { it.role == "user" }) throw IllegalStateException("no user message")

        val stream = body.optBoolean("stream", false)
        val modelId = body.optString("model", "auto").ifBlank { "auto" }
        val created = System.currentTimeMillis() / 1000
        val completionId = "chatcmpl-omni-${System.currentTimeMillis()}"

        if (!stream) {
            // NanoHTTPD worker thread — blocking here is the contract
            val (content, usedModel) = kotlinx.coroutines.runBlocking { runCompletion(modelId, messages) }
            val out = JSONObject()
                .put("id", completionId)
                .put("object", "chat.completion")
                .put("created", created)
                .put("model", usedModel)
                .put(
                    "choices", JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("message", JSONObject().put("role", "assistant").put("content", content))
                            .put("finish_reason", "stop")
                    )
                )
                .put(
                    "usage", JSONObject()
                        .put("prompt_tokens", messages.sumOf { it.content.length / 4 })
                        .put("completion_tokens", content.length / 4)
                        .put("total_tokens", (messages.sumOf { it.content.length } + content.length) / 4)
                )
            return ok(out)
        }

        // SSE with real incremental deltas: cumulative snapshots from the relay
        // are converted to OpenAI-style diffs and flushed as they arrive.
        val pos = PipedOutputStream()
        val pis = PipedInputStream(pos, 64 * 1024)
        scope.launch {
            try {
                var sentLen = 0
                var usedModel = modelId

                fun chunk(deltaContent: String?, finishReason: String?): String {
                    val delta = JSONObject()
                    if (deltaContent != null) delta.put("content", deltaContent)
                    if (sentLen == 0 && deltaContent != null) delta.put("role", "assistant")
                    val choice = JSONObject()
                        .put("index", 0)
                        .put("delta", delta)
                    if (finishReason != null) choice.put("finish_reason", finishReason)
                    return "data: " + JSONObject()
                        .put("id", completionId)
                        .put("object", "chat.completion.chunk")
                        .put("created", created)
                        .put("model", usedModel)
                        .put("choices", JSONArray().put(choice))
                        .toString() + "\n\n"
                }

                pos.write(chunk(null, null).toByteArray()) // role-only first chunk
                pos.flush()

                val events: kotlinx.coroutines.flow.Flow<Any> = if (modelId.indexOf('/') > 0) {
                    val sid = modelId.substring(0, modelId.indexOf('/'))
                    val mid = modelId.substring(modelId.indexOf('/') + 1)
                    val src = app.sourceManager.get(sid)
                        ?: throw IllegalStateException("source '$sid' is not installed — install it in the Store and sign in")
                    src.stream(SourceChatRequest(messages = messages, model = mid)).let { f ->
                        kotlinx.coroutines.flow.flow {
                            f.collect { emit(it as Any) }
                            emit(SourceRouter.RouteEvent.Done("", sid, src.info.name, 0))
                        }
                    }
                } else {
                    app.sourceRouter.chatStream(messages)
                }

                events.collect { ev ->
                    when (ev) {
                        is SourceRouter.RouteEvent.Delta -> {
                            usedModel = "${ev.sourceId}"
                            if (ev.text.length > sentLen) {
                                val piece = ev.text.substring(sentLen)
                                sentLen = ev.text.length
                                pos.write(chunk(piece, null).toByteArray())
                                pos.flush()
                            } else if (ev.text.length < sentLen) {
                                // Snapshot shrank (sanitizer) — resend whole body as one delta
                                val piece = "\u200b" + ev.text
                                sentLen = ev.text.length
                                pos.write(chunk(piece, null).toByteArray())
                                pos.flush()
                            }
                        }
                        is SourceRouter.RouteEvent.Done -> {
                            val full = ev.content.ifBlank { "" }
                            if (full.length > sentLen) {
                                val piece = full.substring(sentLen)
                                sentLen = full.length
                                pos.write(chunk(piece, null).toByteArray())
                            }
                            usedModel = if (ev.sourceName.isBlank()) usedModel else "${ev.sourceId}"
                            pos.write(chunk(null, "stop").toByteArray())
                        }
                    }
                }
                pos.write("data: [DONE]\n\n".toByteArray())
                pos.flush()
            } catch (e: Exception) {
                try {
                    val err = JSONObject()
                        .put("error", JSONObject().put("message", e.message ?: "gateway stream error"))
                    pos.write("data: $err\n\n".toByteArray())
                } catch (_: Exception) {}
            } finally {
                try { pos.write("data: [DONE]\n\n".toByteArray()) } catch (_: Exception) {}
                try { pos.flush() } catch (_: Exception) {}
                try { pos.close() } catch (_: Exception) {}
            }
        }
        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", pis)
        resp.addHeader("X-Accel-Buffering", "no")
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    /**
     * MCP Tunnel endpoint — the device IS an MCP server. Streamable HTTP with
     * JSON responses; initialize → tools/list → tools/call over JSON-RPC 2.0.
     */
    private fun mcpTunnel(session: IHTTPSession): Response {
        if (session.method == Method.GET) {
            // Clients may probe; a plain SSE-less GET is answered with the server card.
            return ok(
                JSONObject()
                    .put("server", "OmniHub Tunnel")
                    .put("protocol", "MCP 2025-03-26 (Streamable HTTP, JSON responses)")
                    .put("endpoint", "/mcp")
            )
        }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: throw IllegalStateException("empty body")
        val rpc = JSONObject(raw)
        val (status, body, sid) = kotlinx.coroutines.runBlocking { app.mcpTunnel.handleRpc(rpc) }
        if (body == null || status != 200) {
            // Notifications and pings: 202 Accepted, empty body per spec
            return newFixedLengthResponse(Response.Status.ACCEPTED, "text/plain", "")
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
        sid?.let { resp.addHeader("Mcp-Session-Id", it) }
        return resp
    }

    /** "sourceId/modelId" → that source directly; "auto"/anything else →
     * SourceRouter fallback chain with soul memory injected.
     */
    private suspend fun runCompletion(modelId: String, messages: List<ChatMessage>): Pair<String, String> {
        val slash = modelId.indexOf('/')
        return if (slash > 0) {
            val sid = modelId.substring(0, slash)
            val mid = modelId.substring(slash + 1)
            val src = app.sourceManager.get(sid)
                ?: throw IllegalStateException("source '$sid' is not installed — install it in the Store and sign in")
            val resp = src.chat(SourceChatRequest(messages = messages, model = mid))
            resp.content to "$sid/${resp.model.ifBlank { mid }}"
        } else {
            val route = app.sourceRouter.chat(messages)
            route.response.content to route.response.model.ifBlank { route.sourceId }
        }
    }

    // ---------- helpers ----------

    private fun ok(j: JSONObject): Response = newFixedLengthResponse(Response.Status.OK, "application/json", j.toString())
    private fun error(status: Response.Status, msg: String): Response =
        newFixedLengthResponse(status, "application/json", JSONObject().put("error", JSONObject().put("message", msg)).toString())

    fun startGateway() {
        start(SOCKET_READ_TIMEOUT, false)
        running.set(true)
    }

    override fun stop() {
        running.set(false)
        super.stop()
    }
}
