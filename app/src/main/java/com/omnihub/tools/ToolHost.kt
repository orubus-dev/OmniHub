package com.omnihub.tools

import android.content.Context
import android.os.BatteryManager
import com.omnihub.mcp.McpClient
import com.omnihub.soul.SoulManager
import com.omnihub.terminal.OmniTerminal
import com.omnihub.workspace.OmniWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * OmniToolHost — every capability the assistant can act through, behind one
 * uniform registry: on-device builtins (terminal, workspace FS, HTTP, memory,
 * device info) plus every tool of every connected MCP server (GitHub, Vercel,
 * Supabase, anything the user pasted).
 *
 * MCP tools are exposed under namespaced names `servername_toolname` so the
 * model never sees collisions between servers, and the tunnel can re-serve
 * the exact same registry to outside clients (device-as-MCP-hub).
 */
class ToolHost(
    private val context: Context,
    private val terminal: OmniTerminal,
    private val workspace: OmniWorkspace,
    private val soul: SoulManager,
    private val mcp: McpClient
) {

    // ---------- definitions ----------

    data class ToolDef(
        val name: String,
        val description: String,
        val schema: JSONObject,          // JSON Schema for inputSchema
        val builtin: Boolean
    )

    private val builtinTools: List<ToolDef> = listOf(
        ToolDef(
            "terminal_run",
            "Run a shell command on this device inside the Omni workspace (sh/toybox). Use for git, ls, cat, grep, file work, anything CLI. " +
                "Output is capped; exit code is reported.",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("command", JSONObject().put("type", "string").put("description", "shell command to run"))
                    .put("timeout_sec", JSONObject().put("type", "integer").put("description", "optional timeout, default 60")))
                .put("required", JSONArray().put("command")),
            true
        ),
        ToolDef(
            "fs_ls",
            "List files in the Omni workspace folder (relative path, default root).",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("path", JSONObject().put("type", "string"))),
            true
        ),
        ToolDef(
            "fs_read",
            "Read a text file from the Omni workspace (relative path).",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("path")),
            true
        ),
        ToolDef(
            "fs_write",
            "Create or overwrite a text file in the Omni workspace (relative path).",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string"))
                    .put("content", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("path").put("content")),
            true
        ),
        ToolDef(
            "http_fetch",
            "Perform an HTTP request and return the response body as text (capped at ~6k chars). Use for REST APIs and webhooks.",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("url", JSONObject().put("type", "string"))
                    .put("method", JSONObject().put("type", "string").put("description", "GET/POST/PUT/DELETE, default GET"))
                    .put("body", JSONObject().put("type", "string"))
                    .put("headers", JSONObject().put("type", "object").put("description", "map of header name to value")))
                .put("required", JSONArray().put("url")),
            true
        ),
        ToolDef(
            "memory_save",
            "Persist a durable fact or preference about the user (visible across all providers).",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("text", JSONObject().put("type", "string"))
                    .put("topic", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("text")),
            true
        ),
        ToolDef(
            "memory_recall",
            "Search persistent memory for facts matching a query.",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("query", JSONObject().put("type", "string"))),
            true
        ),
        ToolDef(
            "device_info",
            "Report device status: battery, storage, time, app workspace path.",
            JSONObject().put("type", "object").put("properties", JSONObject()),
            true
        ),
        ToolDef(
            "tool_schema",
            "Return the exact parameter schema of another tool, for when you are unsure how to call it.",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("name")),
            true
        )
    )

    /** exposedName -> RemoteRef for MCP tools. */
    data class RemoteRef(val serverId: String, val serverName: String, val toolName: String)

    private val mcpTools = ConcurrentHashMap<String, RemoteRef>()

    /** Rebuild the MCP namespace map from the currently connected servers. */
    fun refreshMcpTools() {
        mcpTools.clear()
        val used = builtinTools.map { it.name }.toMutableSet()
        for (server in mcp.listServers()) {
            if (!server.connected) continue
            val prefix = sanitize(server.name.ifBlank { server.url })
            for (tool in server.toolDetails) {
                var exposed = "${prefix}_${sanitize(tool.name)}"
                var n = 2
                while (used.contains(exposed)) { exposed = "${prefix}_${sanitize(tool.name)}_$n"; n++ }
                used.add(exposed)
                mcpTools[exposed] = RemoteRef(server.id, server.name, tool.name)
            }
        }
    }

    private fun sanitize(s: String) =
        s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "srv" }

    /** All tools currently callable, builtins first. */
    fun allTools(): List<ToolDef> {
        refreshMcpTools()
        val defs = builtinTools.toMutableList()
        val servers = mcp.listServers()
        for ((exposed, ref) in mcpTools) {
            val detail = servers.find { it.id == ref.serverId }
                ?.toolDetails?.find { it.name == ref.toolName } ?: continue
            val schema = try { JSONObject(detail.inputSchema) } catch (_: Exception) { JSONObject() }
            defs.add(
                ToolDef(
                    exposed,
                    "[${ref.serverName}] ${detail.description.ifBlank { ref.toolName }}",
                    schema,
                    false
                )
            )
        }
        return defs
    }

    fun remoteRefCount(): Int = mcpTools.size

    // ---------- execution ----------

    suspend fun execute(name: String, arguments: JSONObject, origin: String = "AI"): ToolResult {
        return try {
            val builtin = builtinTools.find { it.name == name }
            val value: String = when {
                builtin != null -> execBuiltin(name, arguments, origin)
                mcpTools.containsKey(name) -> {
                    val ref = mcpTools[name]!!
                    val server = mcp.listServers().find { it.id == ref.serverId }
                        ?: return ToolResult(false, "MCP server '${ref.serverName}' is disconnected")
                    val args = JSONObject()
                    arguments.keys().forEach { k -> args.put(k, arguments.get(k)) }
                    mcp.callToolByRef(ref.serverId, ref.toolName, args)
                        .fold({ it }, { throw it })
                }
                else -> return ToolResult(false, "Unknown tool '$name'")
            }
            ToolResult(true, cap(value))
        } catch (e: Exception) {
            ToolResult(false, cap(e.message ?: e.toString()))
        }
    }

    data class ToolResult(val ok: Boolean, val output: String)

    private suspend fun execBuiltin(name: String, a: JSONObject, origin: String): String =
        withContext(Dispatchers.IO) {
            when (name) {
                "terminal_run" -> {
                    val cmd = a.optString("command")
                    if (cmd.isBlank()) throw IllegalArgumentException("command required")
                    val r = terminal.run(cmd, timeoutSec = a.optLong("timeout_sec", 60L).coerceIn(1, 300))
                    buildString {
                        append("exit=${r.exitCode}\n")
                        if (r.stdout.isNotBlank()) append(r.stdout)
                        if (r.stderr.isNotBlank()) append("\n[stderr]\n${r.stderr}")
                    }.trim()
                }
                "fs_ls" -> {
                    val dir = a.optString("path", "")
                    val files = workspace.list(dir)
                    if (files.isEmpty()) "(empty)"
                    else files.joinToString("\n") { f ->
                        (if (f.isDirectory) "d " else "- ") + f.name +
                            (if (f.isFile) " (${f.length()}B)" else "")
                    }
                }
                "fs_read" -> {
                    val p = a.optString("path")
                    val f = workspace.resolve(p)
                    if (!f.exists()) throw IllegalArgumentException("not found: $p")
                    if (f.length() > 200_000) f.readText().take(100_000) + "\n…[truncated]" else f.readText()
                }
                "fs_write" -> {
                    val f = workspace.writeText(a.optString("path"), a.optString("content"))
                    "written ${f.length()}B to ${f.absolutePath}"
                }
                "http_fetch" -> execHttp(a)
                "memory_save" -> {
                    val text = a.optString("text")
                    if (text.isBlank()) throw IllegalArgumentException("text required")
                    soul.rememberExplicit(text, a.optString("topic", "fact"))
                    "saved to memory"
                }
                "memory_recall" -> {
                    soul.generatePromptContext(maxUnits = 12).ifBlank { "(memory empty)" }
                }
                "device_info" -> deviceInfo()
                "tool_schema" -> {
                    val n = a.optString("name")
                    val def = allTools().find { it.name == n }
                        ?: throw IllegalArgumentException("unknown tool: $n")
                    JSONObject()
                        .put("name", def.name)
                        .put("description", def.description)
                        .put("parameters", def.schema)
                        .toString()
                }
                else -> throw IllegalArgumentException("not builtin: $name")
            }
        }

    private fun execHttp(a: JSONObject): String {
        val urlStr = a.optString("url")
        require(urlStr.startsWith("http://") || urlStr.startsWith("https://")) { "http(s) url required" }
        require(urlStr.length < 2000) { "url too long" }
        val method = a.optString("method", "GET").uppercase(Locale.US)
        val conn = URL(urlStr).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = if (method in setOf("GET", "POST", "PUT", "DELETE", "PATCH")) method else "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        a.optJSONObject("headers")?.let { h ->
            h.keys().forEach { k ->
                if (k.length < 128 && !k.lowercase().contains("cookie")) {
                    conn.setRequestProperty(k, h.optString(k).take(1024))
                }
            }
        }
        val body = a.optString("body", "")
        if (body.isNotBlank() && method != "GET") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return "HTTP $code\n$text"
    }

    private fun deviceInfo(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        val wsRoot = workspace.root()
        val usable = wsRoot.usableSpace / (1024 * 1024)
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        return JSONObject()
            .put("battery_pct", pct)
            .put("charging", charging)
            .put("storage_free_mb", usable)
            .put("workspace", wsRoot.absolutePath)
            .put("time", ts)
            .toString()
    }

    private fun cap(s: String, max: Int = 6000): String {
        val t = s.trim()
        if (t.length <= max) return t
        val head = max * 2 / 3
        val tail = max - head
        return t.take(head) + "\n…[elided ${t.length - max} chars]…\n" + t.takeLast(tail)
    }
}
