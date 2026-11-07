package com.omnihub.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omnihub.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * OmniHub MCP client — real Model Context Protocol.
 *
 * Implements JSON-RPC 2.0 over:
 *  - Streamable HTTP (current spec): POST initialize -> Mcp-Session-Id header ->
 *    notifications/initialized -> tools/list -> tools/call. Response bodies may be
 *    application/json OR text/event-stream; both are parsed.
 *  - Legacy HTTP+SSE: GET endpoint -> wait for "endpoint" event carrying the POST URL ->
 *    RPCs are POSTed there, replies arrive on the SSE stream (correlated by id).
 *
 * STDIO is declared but requires the OmniTerminal substrate (v1.1).
 */
class McpClient(private val context: Context) {

    enum class Transport { SSE, HTTP, STDIO }

    data class McpTool(
        val name: String,
        val description: String = "",
        val inputSchema: String = "{}"
    )

    data class McpServer(
        val id: String,
        val name: String,
        val url: String,
        val transport: Transport = Transport.HTTP,
        val connected: Boolean = false,
        val lastError: String? = null,
        val tools: List<String> = emptyList(),
        val toolDetails: List<McpTool> = emptyList(),
        val protocolVersion: String? = null,
        val serverInfo: String? = null
    )

    private data class Session(
        val transport: Transport,
        val postEndpoint: String,
        val sessionId: String? = null,
        val stream: BufferedReader? = null,
        val response: okhttp3.Response? = null
    )

    private val prefs = context.getSharedPreferences("omnihub_mcp", Context.MODE_PRIVATE)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val ids = AtomicInteger(1)

    private fun nextId(): Int = ids.getAndIncrement()
    private val JSON = "application/json".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ---------- persistence ----------

    fun listServers(): List<McpServer> {
        return prefs.all.keys
            .filter { it.startsWith("server_") }
            .mapNotNull { key ->
                val raw = prefs.getString(key, null) ?: return@mapNotNull null
                try {
                    val j = JSONObject(raw)
                    val details = mutableListOf<McpTool>()
                    j.optJSONArray("toolDetails")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val t = arr.optJSONObject(i) ?: continue
                            details.add(
                                McpTool(
                                    name = t.optString("name"),
                                    description = t.optString("description"),
                                    inputSchema = t.optString("inputSchema", "{}")
                                )
                            )
                        }
                    }
                    McpServer(
                        id = j.getString("id"),
                        name = j.getString("name"),
                        url = j.getString("url"),
                        transport = Transport.valueOf(j.optString("transport", "HTTP")),
                        connected = j.optBoolean("connected", false),
                        lastError = j.optString("lastError", null).takeIf { !it.isNullOrBlank() },
                        tools = j.optJSONArray("tools")?.let { a ->
                            (0 until a.length()).map { a.getString(it) }
                        } ?: emptyList(),
                        toolDetails = details,
                        protocolVersion = j.optString("protocolVersion", null).takeIf { !it.isNullOrBlank() },
                        serverInfo = j.optString("serverInfo", null).takeIf { !it.isNullOrBlank() }
                    )
                } catch (_: Exception) { null }
            }
    }

    private fun saveServer(server: McpServer) {
        val j = JSONObject().apply {
            put("id", server.id)
            put("name", server.name)
            put("url", server.url)
            put("transport", server.transport.name)
            put("connected", server.connected)
            put("lastError", server.lastError ?: "")
            put("tools", JSONArray(server.tools))
            put("toolDetails", JSONArray().apply {
                server.toolDetails.forEach { t ->
                    put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("inputSchema", t.inputSchema)
                    })
                }
            })
            put("protocolVersion", server.protocolVersion ?: "")
            put("serverInfo", server.serverInfo ?: "")
        }
        prefs.edit().putString("server_${server.id}", j.toString()).apply()
    }

    private fun validateUrl(raw: String): String {
        val u = raw.trim()
        require(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("sse://")) {
            "MCP URL must start with https:// or sse://"
        }
        require(u.length < 500) { "URL too long" }
        require(!u.contains(" ") && !u.contains("<") && !u.contains(">")) {
            "Invalid characters in URL"
        }
        return u.replace("sse://", "https://")
    }

    // ---------- JSON-RPC ----------

    private fun rpcRequest(id: Int, method: String, params: JSONObject?): JSONObject {
        val o = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) o.put("params", params)
        return o
    }

    private fun rpcNotification(method: String, params: JSONObject? = null): JSONObject {
        val o = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
        if (params != null) o.put("params", params)
        return o
    }

    private fun initializeParams(): JSONObject = JSONObject().apply {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", JSONObject())
        put("clientInfo", JSONObject().put("name", "OmniHub").put("version", CLIENT_VERSION))
    }

    private fun post(url: String, payload: JSONObject, session: String?, sseOnly: Boolean): RpcOutcome {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", if (sseOnly) "text/event-stream" else "application/json, text/event-stream")
            .addHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
        tokenFor(url)?.let { builder.addHeader("Authorization", "Bearer $it") }
        session?.let { builder.addHeader("Mcp-Session-Id", it) }
        http.newCall(builder.build()).execute().use { resp ->
            val sid = resp.header("Mcp-Session-Id")
            val ctype = resp.header("Content-Type") ?: ""
            val body = resp.body?.string().orEmpty()
            return RpcOutcome(resp.code, sid, ctype, body)
        }
    }

    private data class RpcOutcome(val code: Int, val session: String?, val contentType: String, val body: String)

    /**
     * Parse either an application/json RPC response or an SSE stream body and return
     * the JSON-RPC response object whose id matches [wantId] (or the last valid one when null).
     */
    private fun parseRpcOutcome(outcome: RpcOutcome, wantId: Int?): JSONObject? {
        if (outcome.body.isBlank()) return null
        if (outcome.contentType.contains("text/event-stream")) return parseSseBody(outcome.body, wantId)
        return try {
            val j = JSONObject(outcome.body)
            if (wantId == null || j.optInt("id", -1) == wantId) j else null
        } catch (_: Exception) { null }
    }

    private fun parseSseBody(body: String, wantId: Int?): JSONObject? {
        var last: JSONObject? = null
        for (line in body.lines()) {
            val l = line.trim()
            if (!l.startsWith("data:")) continue
            val data = l.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            try {
                val j = JSONObject(data)
                if (j.has("method")) continue // server notification, not a response
                if (wantId == null || j.optInt("id", -1) == wantId) return j
                last = j
            } catch (_: Exception) { /* not JSON, skip */ }
        }
        return last
    }

    private fun extractError(json: JSONObject?): String? {
        if (json == null) return null
        val err = json.optJSONObject("error") ?: return null
        return "MCP error ${err.optInt("code")}: ${err.optString("message")}"
    }

    private fun parseTools(result: JSONObject): List<McpTool> {
        val arr = result.optJSONArray("tools") ?: return emptyList()
        val tools = mutableListOf<McpTool>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            tools.add(
                McpTool(
                    name = t.optString("name"),
                    description = t.optString("description"),
                    inputSchema = t.optJSONObject("inputSchema")?.toString() ?: "{}"
                )
            )
        }
        return tools
    }

    // ---------- connect ----------

    suspend fun connect(url: String, name: String = "MCP Server"): Result<McpServer> =
        withContext(Dispatchers.IO) {
            val safeUrl = try { validateUrl(url) } catch (e: Exception) { return@withContext Result.failure(e) }
            val id = java.util.UUID.randomUUID().toString()

            // 1) Streamable HTTP
            handshakeStreamableHttp(safeUrl)?.let { hs ->
                sessions[id] = Session(Transport.HTTP, safeUrl, hs.sessionId)
                val server = McpServer(
                    id = id,
                    name = name.ifBlank { hs.serverName ?: "MCP @ ${safeUrl.take(40)}" },
                    url = safeUrl,
                    transport = Transport.HTTP,
                    connected = true,
                    tools = hs.tools.map { it.name },
                    toolDetails = hs.tools,
                    protocolVersion = hs.protocolVersion,
                    serverInfo = hs.serverName
                )
                saveServer(server)
                return@withContext Result.success(server)
            }

            // 2) Legacy HTTP+SSE
            try {
                handshakeLegacySse(safeUrl)?.let { hs ->
                    hs.session?.let { sessions[id] = it }
                    val server = McpServer(
                        id = id,
                        name = name.ifBlank { hs.serverName ?: "MCP @ ${safeUrl.take(40)}" },
                        url = safeUrl,
                        transport = Transport.SSE,
                        connected = true,
                        tools = hs.tools.map { it.name },
                        toolDetails = hs.tools,
                        protocolVersion = hs.protocolVersion,
                        serverInfo = hs.serverName
                    )
                    saveServer(server)
                    return@withContext Result.success(server)
                }
            } catch (e: Exception) {
                val failed = McpServer(
                    id = id, name = name.ifBlank { "MCP @ ${safeUrl.take(40)}" }, url = safeUrl,
                    connected = false, lastError = e.message ?: "Handshake failed"
                )
                saveServer(failed)
                return@withContext Result.failure(e)
            }

            val failed = McpServer(
                id = id, name = name.ifBlank { "MCP @ ${safeUrl.take(40)}" }, url = safeUrl,
                connected = false,
                lastError = "No MCP handshake: endpoint is not Streamable HTTP or SSE"
            )
            saveServer(failed)
            Result.failure(IllegalStateException(failed.lastError))
        }

    private data class Handshake(
        val tools: List<McpTool>,
        val protocolVersion: String,
        val serverName: String?,
        val sessionId: String? = null,
        val session: Session? = null
    )

    private fun handshakeStreamableHttp(url: String): Handshake? {
        val initId = nextId()
        val outcome = try {
            post(url, rpcRequest(initId, "initialize", initializeParams()), null, sseOnly = false)
        } catch (_: Exception) {
            return null
        }
        if (outcome.code == 404 || outcome.code == 405) return null // streamable not supported here
        val initResp = parseRpcOutcome(outcome, initId) ?: return null
        (extractError(initResp))?.let { throw IllegalStateException(it) }
        val result = initResp.optJSONObject("result") ?: return null
        val protoVer = result.optString("protocolVersion", PROTOCOL_VERSION)
        val serverName = result.optJSONObject("serverInfo")?.let {
            "${it.optString("name")} ${it.optString("version")}"
        }
        val sessionId = outcome.session

        // notifications/initialized (fire and forget)
        try {
            post(url, rpcNotification("notifications/initialized"), sessionId, sseOnly = false)
        } catch (_: Exception) {}

        // tools/list
        val listId = nextId()
        val listOutcome = post(url, rpcRequest(listId, "tools/list", JSONObject().put("limit", 200)), sessionId, sseOnly = false)
        val listResp = parseRpcOutcome(listOutcome, listId)
        val tools = listResp?.optJSONObject("result")?.let { parseTools(it) } ?: emptyList()

        return Handshake(tools, protoVer, serverName, sessionId = sessionId)
    }

    private fun handshakeLegacySse(url: String): Handshake? {
        val getBuilder = Request.Builder().url(url)
            .addHeader("Accept", "text/event-stream")
            .addHeader("Cache-Control", "no-cache")
        tokenFor(url)?.let { getBuilder.addHeader("Authorization", "Bearer $it") }
        val resp = http.newCall(getBuilder.build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            return null
        }
        val reader = resp.body?.byteStream()?.bufferedReader() ?: run { resp.close(); return null }

        // Read until the "endpoint" event gives us the POST URL
        var postEndpoint: String? = null
        var event = ""
        var deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    val data = line.removePrefix("data:").trim()
                    if (event == "endpoint" || postEndpoint == null && data.isNotEmpty() && data.startsWith("/")) {
                        postEndpoint = if (data.startsWith("http")) data
                        else java.net.URI(url).resolve(data).toString()
                        break
                    }
                }
            }
        }
        if (postEndpoint == null) { resp.close(); return null }

        val session = Session(Transport.SSE, postEndpoint, sessionId = null, stream = reader, response = resp)

        // initialize over the POST endpoint, response arrives on the SSE stream
        val initId = nextId()
        postBlind(postEndpoint, rpcRequest(initId, "initialize", initializeParams()), session.sessionId)
        val initResp = readSseUntil(reader, initId, 20_000) ?: run { closeSession(session); return null }
        val result = initResp.optJSONObject("result") ?: run { closeSession(session); return null }
        val protoVer = result.optString("protocolVersion", PROTOCOL_VERSION)
        val serverName = result.optJSONObject("serverInfo")?.let { "${it.optString("name")} ${it.optString("version")}" }

        postBlind(postEndpoint, rpcNotification("notifications/initialized"), session.sessionId)

        val listId = nextId()
        postBlind(postEndpoint, rpcRequest(listId, "tools/list", JSONObject().put("limit", 200)), session.sessionId)
        val listResp = readSseUntil(reader, listId, 20_000) ?: run { closeSession(session); return null }
        val tools = listResp.optJSONObject("result")?.let { parseTools(it) } ?: emptyList()

        // Keep the stream open for future calls; Session carries reader+response.
        return Handshake(tools = tools, protocolVersion = protoVer, serverName = serverName, session = session)
    }

    private fun postBlind(url: String, payload: JSONObject, sessionId: String?) {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
        tokenFor(url)?.let { builder.addHeader("Authorization", "Bearer $it") }
        sessionId?.let { builder.addHeader("Mcp-Session-Id", it) }
        try {
            http.newCall(builder.build()).execute().use { /* response via SSE stream */ }
        } catch (_: Exception) {}
    }

    private fun readSseUntil(reader: BufferedReader, wantId: Int, timeoutMs: Long): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val dataBuf = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val line = try { reader.readLine() } catch (_: Exception) { return null } ?: return null
            when {
                line.startsWith("data:") -> { dataBuf.setLength(0); dataBuf.append(line.removePrefix("data:").trim()) }
                line.isEmpty() && dataBuf.isNotEmpty() -> {
                    val data = dataBuf.toString()
                    dataBuf.setLength(0)
                    if (data.isEmpty() || data == "[DONE]") continue
                    try {
                        val j = JSONObject(data)
                        if (j.has("method")) continue
                        if (j.optInt("id", -1) == wantId) return j
                    } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    private fun closeSession(s: Session) {
        try { s.stream?.close() } catch (_: Exception) {}
        try { s.response?.close() } catch (_: Exception) {}
    }

    // ---------- tools ----------

    suspend fun refreshTools(serverId: String): Result<McpServer> = withContext(Dispatchers.IO) {
        val stored = listServers().find { it.id == serverId }
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown server"))
        val id = nextId()
        try {
            when (stored.transport) {
                Transport.HTTP -> {
                    val session = sessions[serverId]
                    val outcome = post(
                        stored.url,
                        rpcRequest(id, "tools/list", JSONObject().put("limit", 200)),
                        session?.sessionId, sseOnly = false
                    )
                    val resp = parseRpcOutcome(outcome, id)
                    val err = extractError(resp)
                    if (err != null) return@withContext Result.failure(IllegalStateException(err))
                    val tools = resp?.optJSONObject("result")?.let { parseTools(it) } ?: emptyList()
                    val updated = stored.copy(connected = true, lastError = null, tools = tools.map { it.name }, toolDetails = tools)
                    saveServer(updated)
                    Result.success(updated)
                }
                Transport.SSE -> {
                    val session = sessions[serverId]
                        ?: return@withContext Result.failure(IllegalStateException("SSE session lost — reconnect"))
                    val listId = nextId()
                    postBlind(session.postEndpoint, rpcRequest(listId, "tools/list", JSONObject().put("limit", 200)), session.sessionId)
                    val resp = readSseUntil(session.stream!!, listId, 20_000)
                        ?: return@withContext Result.failure(IllegalStateException("No response from SSE stream"))
                    val tools = resp.optJSONObject("result")?.let { parseTools(it) } ?: emptyList()
                    val updated = stored.copy(connected = true, lastError = null, tools = tools.map { it.name }, toolDetails = tools)
                    saveServer(updated)
                    Result.success(updated)
                }
                Transport.STDIO -> Result.failure(IllegalStateException("STDIO transport requires OmniTerminal substrate (v1.1)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun callTool(
        server: McpServer,
        toolName: String,
        arguments: Map<String, Any> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
                .put("name", toolName)
                .put("arguments", JSONObject(arguments))
            val id = nextId()
            val respJson: JSONObject? = when (server.transport) {
                Transport.HTTP -> {
                    val session = sessions[server.id]
                    val outcome = post(server.url, rpcRequest(id, "tools/call", params), session?.sessionId, sseOnly = false)
                    parseRpcOutcome(outcome, id)
                }
                Transport.SSE -> {
                    val session = sessions[server.id]
                        ?: return@withContext Result.failure(IllegalStateException("SSE session lost — reconnect the server"))
                    postBlind(session.postEndpoint, rpcRequest(id, "tools/call", params), session.sessionId)
                    readSseUntil(session.stream!!, id, 120_000)
                }
                Transport.STDIO -> return@withContext Result.failure(
                    IllegalStateException("STDIO transport requires OmniTerminal substrate (v1.1)")
                )
            }
            val err = extractError(respJson)
            if (err != null) return@withContext Result.failure(IllegalStateException(err))
            val result = respJson?.optJSONObject("result")
                ?: return@withContext Result.failure(IllegalStateException("Empty MCP result"))
            if (result.optBoolean("isError", false)) {
                return@withContext Result.failure(IllegalStateException(textOf(result).ifBlank { "Tool reported error" }))
            }
            Result.success(textOf(result))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun textOf(result: JSONObject): String {
        val sb = StringBuilder()
        result.optJSONArray("content")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "text" -> sb.append(item.optString("text")).append('\n')
                    "resource" -> sb.append(item.optJSONObject("resource")?.optString("text").orEmpty()).append('\n')
                }
            }
        }
        return sb.toString().trim()
    }

    // ---------- auth / misc (API-compatible with previous surface) ----------

    fun openAuthorization(builtIn: BuiltInMcp) {
        val url = builtIn.docsUrl ?: return
        openInBrowser(url)
    }

    fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun disconnect(serverId: String) {
        sessions.remove(serverId)?.let { closeSession(it) }
        prefs.edit().remove("server_$serverId").apply()
    }

    fun setToken(serverId: String, token: String) {
        SecureStore.putSecret(context, "mcp_token_$serverId", token)
    }

    fun getToken(serverId: String): String? =
        SecureStore.getSecret(context, "mcp_token_$serverId")

    private fun tokenFor(url: String): String? {
        return listServers().find { s ->
            url.startsWith(s.url.trimEnd('/')) || s.url.trimEnd('/') == url.trimEnd('/')
        }?.let { getToken(it.id) }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val CLIENT_VERSION = "1.1.0"
    }
}
