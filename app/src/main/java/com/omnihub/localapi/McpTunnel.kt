package com.omnihub.localapi

import com.omnihub.mcp.McpClient
import com.omnihub.tools.ToolHost
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * OmniHub MCP Tunnel — the DEVICE is an MCP server.
 *
 * Served by the LocalGateway at  http://<device>:8099/mcp  (Streamable HTTP,
 * JSON responses). Any MCP client — Claude Desktop, Cursor, Claude Code,
 * another phone — can connect and gets the full registry:
 *
 *   - on-device tools: terminal_run, workspace files, http_fetch, memory,
 *     device_info  (the phone becomes a remotely drivable agent host)
 *   - every tool of every MCP server the user pasted in OmniHub (GitHub,
 *     Vercel, Supabase, …) proxied server-side, so one URL fronts them all.
 *
 * JSON-RPC 2.0 methods: initialize, notifications/initialized, ping,
 * tools/list, tools/call. Stateless apart from echoing a session id.
 */
class McpTunnel(
    private val toolHost: ToolHost,
    private val mcp: McpClient
) {

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        private const val TUNNEL_TERMINAL = "omni_terminal_run"
    }

    /** Name -> RemoteRef for proxied MCP tools, rebuilt on every tools/list|call. */
    private data class ProxyRef(val serverId: String, val serverName: String, val toolName: String)
    private var proxyMap: Map<String, ProxyRef> = emptyMap()

    private fun rebuildProxyMap() {
        val map = mutableMapOf<String, ProxyRef>()
        val used = mutableSetOf<String>()
        toolHost.allTools().forEach { used.add(it.name) }
        for (server in mcp.listServers()) {
            if (!server.connected) continue
            val prefix = sanitize(server.name.ifBlank { server.url })
            for (tool in server.toolDetails) {
                var exposed = "${prefix}_${sanitize(tool.name)}"
                var n = 2
                while (used.contains(exposed)) { exposed = "${prefix}_${sanitize(tool.name)}_$n"; n++ }
                used.add(exposed)
                map[exposed] = ProxyRef(server.id, server.name, tool.name)
            }
        }
        proxyMap = map
    }

    private fun sanitize(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "srv" }

    // ---------- JSON-RPC handling ----------

    /**
     * Handle one POST /mcp JSON-RPC request. Returns the HTTP status + body the
     * gateway should send. [sessionIdOut] carries the Mcp-Session-Id to echo.
     */
    suspend fun handleRpc(body: JSONObject): RpcHttp {
        val method = body.optString("method")
        val id = body.opt("id")
        val isNotification = !body.has("id")

        return when {
            method == "initialize" -> {
                val result = JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", true)))
                    .put("serverInfo", JSONObject()
                        .put("name", "OmniHub Tunnel")
                        .put("version", "1.2.0"))
                    .put("instructions",
                        "This is the user's DEVICE. Tools named omni_* run on the phone (terminal, files, memory). " +
                        "Other tools are proxied live from the user's connected MCP servers.")
                RpcHttp(200, rpcResult(id, result), sessionId = newSessionId())
            }
            isNotification -> RpcHttp(202, null) // notifications/initialized etc.
            method == "ping" -> RpcHttp(200, rpcResult(id, JSONObject()))
            method == "tools/list" -> RpcHttp(200, rpcResult(id, toolsListResult()))
            method == "tools/call" -> {
                val params = body.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                callTool(name, args, id)
            }
            method == "resources/list" -> RpcHttp(200, rpcResult(id,
                JSONObject().put("resources", JSONArray())))
            method == "prompts/list" -> RpcHttp(200, rpcResult(id,
                JSONObject().put("prompts", JSONArray())))
            method.startsWith("logging/") -> RpcHttp(202, null)
            else -> RpcHttp(200, rpcError(id, -32601, "Method not found: $method"))
        }
    }

    data class RpcHttp(val status: Int, val body: JSONObject?, val sessionId: String? = null)

    private fun rpcResult(id: Any?, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun newSessionId(): String =
        "omni-" + java.util.UUID.randomUUID().toString().take(8)

    // ---------- tools/list ----------

    private fun toolsListResult(): JSONObject {
        rebuildProxyMap()
        val arr = JSONArray()
        for (t in toolHost.allTools()) {
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("inputSchema", t.schema)
            )
        }
        for ((exposed, ref) in proxyMap) {
            val detail = mcp.serverById(ref.serverId)?.toolDetails?.find { it.name == ref.toolName } ?: continue
            val schema = try { JSONObject(detail.inputSchema) } catch (_: Exception) { JSONObject() }
            arr.put(
                JSONObject()
                    .put("name", exposed)
                    .put("description", "[${ref.serverName}] ${detail.description.ifBlank { ref.toolName }}")
                    .put("inputSchema", schema)
            )
        }
        return JSONObject().put("tools", arr)
    }

    // ---------- tools/call ----------

    private suspend fun callTool(name: String, args: JSONObject, id: Any?): RpcHttp {
        rebuildProxyMap()
        return try {
            val proxied = proxyMap[name]
            val text: String = if (proxied != null) {
                mcp.callToolByRef(proxied.serverId, proxied.toolName, args)
                    .fold({ it }, { throw it })
            } else {
                // Builtin path — attribute tunnel-originated terminal work
                if (name == TUNNEL_TERMINAL) {
                    val r = toolHost.execute(name, args, origin = "TUNNEL")
                    if (!r.ok) throw IllegalStateException(r.output) else r.output
                } else {
                    val r = toolHost.execute(name, args, origin = "TUNNEL")
                    if (!r.ok) throw IllegalStateException(r.output) else r.output
                }
            }
            val content = JSONArray().put(
                JSONObject().put("type", "text").put("text", text)
            )
            RpcHttp(200, rpcResult(id, JSONObject().put("content", content).put("isError", false)))
        } catch (e: Exception) {
            val content = JSONArray().put(
                JSONObject().put("type", "text").put("text", e.message ?: e.toString())
            )
            // Spec: tool EXECUTION errors are results with isError=true, not JSON-RPC errors
            RpcHttp(200, rpcResult(id, JSONObject().put("content", content).put("isError", true)))
        }
    }

    // ---------- LAN discovery helper ----------

    fun lanAddresses(port: Int): List<String> {
        val out = mutableListOf<String>()
        try {
            val nis = java.net.NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        out.add("http://${addr.hostAddress}:$port/mcp")
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
