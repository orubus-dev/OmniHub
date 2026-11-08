package com.omnihub.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnihub.OmniHubApp
import com.omnihub.ui.theme.OmniAmber
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen(onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Skills", "Behavior", "Tone", "MCP Server")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customize", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> SkillsTab()
                1 -> BehaviorTab()
                2 -> ToneTab()
                3 -> McpServerTab()
            }
        }
    }
}

@Composable
private fun SkillsTab() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Skills & Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Enable or disable skills the model can use. MCP servers also appear here once connected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )

        listOf(
            "Web Search" to true,
            "Code Interpreter" to true,
            "Image Generation" to false,
            "File Analysis" to true,
            "Browser Control" to false,
            "Soul Memory" to true
        ).forEach { (name, enabled) ->
            var checked by remember { mutableStateOf(enabled) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = checked, onCheckedChange = { checked = it })
                }
            }
        }
    }
}

@Composable
private fun BehaviorTab() {
    var systemPrompt by remember {
        mutableStateOf("You are OmniHub, a helpful, truthful, and maximally useful AI assistant running on the user’s device.")
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Behavior", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Custom instructions that shape every response.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            label = { Text("Custom Instructions") },
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
private fun ToneTab() {
    val tones = listOf("Neutral", "Friendly", "Professional", "Witty", "Direct", "Empathetic")
    var selected by remember { mutableStateOf("Direct") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Tone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        tones.forEach { tone ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected = tone },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected == tone) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == tone, onClick = { selected = tone })
                    Spacer(Modifier.width(8.dp))
                    Text(tone, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun McpServerTab() {
    val app = OmniHubApp.instance
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf(app.mcp.listServers()) }

    // One-tap templates: the official remote MCP endpoints people actually paste
    data class Template(val label: String, val endpoint: String, val hint: String, val tokenHint: String?)
    val templates = listOf(
        Template("GitHub", "https://api.githubcopilot.com/mcp/", "Repos · issues · PRs · code search", "GitHub PAT (github.com/settings/tokens)"),
        Template("Vercel", "https://mcp.vercel.com", "Deployments · projects · domains · logs", "Vercel access token"),
        Template("Supabase", "https://mcp.supabase.com/mcp", "Database · auth · storage · edge functions", "Supabase access token"),
        Template("Context7", "https://mcp.context7.com/mcp", "Up-to-date library docs", null)
    )

    fun connect(target: String, tok: String?) {
        if (target.isBlank()) return
        status = "Handshaking with ${target.take(48)}…"
        scope.launch {
            val result = app.mcp.connect(target, token = tok?.trim()?.takeIf { it.isNotBlank() })
            result.fold(
                onSuccess = { s ->
                    status = if (s.connected)
                        "✓ ${s.name} — ${s.tools.size} tools live in chat"
                    else "Saved but handshake failed: ${s.lastError}"
                    servers = app.mcp.listServers()
                },
                onFailure = { e ->
                    status = "Failed: ${e.message}"
                    servers = app.mcp.listServers()
                }
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MCP Servers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Paste any MCP server link (Streamable HTTP or SSE). OmniHub runs the full handshake — initialize, session, tools — and every tool becomes available to your AI, in chat and over the tunnel.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )

        // Quick add
        Text("Quick add", style = MaterialTheme.typography.titleSmall)
        templates.forEach { t ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    url = t.endpoint
                    connect(t.endpoint, token.takeIf { it.isNotBlank() })
                },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(t.label, fontWeight = FontWeight.SemiBold, color = OmniAmber)
                    Text("${t.endpoint} — ${t.hint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    t.tokenHint?.let {
                        Text("Needs token: $it (paste below first)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }

        Divider()

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("MCP Server URL") },
            placeholder = { Text("https://mcp.example.com/mcp") },
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bearer token (optional)") },
            placeholder = { Text("ghp_… / vercel_… / sbp_…") },
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        Button(
            onClick = {
                val target = url.trim()
                url = ""
                connect(target, token)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = url.isNotBlank()
        ) {
            Icon(Icons.Default.Link, null)
            Spacer(Modifier.width(8.dp))
            Text("Connect MCP Server")
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Divider()

        // Device-as-tunnel card
        val gwRunning = com.omnihub.localapi.LocalGateway.running.get()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = OmniAmber.copy(alpha = 0.08f))
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeviceHub, null, tint = OmniAmber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Device tunnel (this phone = an MCP server)", fontWeight = FontWeight.SemiBold)
                }
                if (gwRunning) {
                    val lan = app.mcpTunnel.lanAddresses(com.omnihub.localapi.LocalGateway.DEFAULT_PORT)
                    Text(
                        "Point any MCP client (Claude Desktop, Cursor, Claude Code) at:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "http://127.0.0.1:${com.omnihub.localapi.LocalGateway.DEFAULT_PORT}/mcp" +
                            (lan.firstOrNull()?.let { "\n$it (LAN)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = OmniAmber,
                        modifier = Modifier.clickable {
                            clipboard.setText(
                                androidx.compose.ui.text.AnnotatedString(
                                    lan.firstOrNull()
                                        ?: "http://127.0.0.1:${com.omnihub.localapi.LocalGateway.DEFAULT_PORT}/mcp"
                                )
                            )
                            status = "Tunnel URL copied"
                        }
                    )
                    Text(
                        "They get: on-device terminal + files + memory, plus every MCP server connected above — proxied through your phone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        "Enable the Local Gateway in Settings → OmniRoute to expose this device as an MCP endpoint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Divider()

        Text("Connected", style = MaterialTheme.typography.titleSmall)
        if (servers.isEmpty()) {
            Text("No MCP servers yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        servers.forEach { server ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(server.name, fontWeight = FontWeight.Medium)
                        Text(
                            if (server.connected)
                                "Connected — ${server.tools.size} tools" +
                                    (server.tools.take(5).takeIf { it.isNotEmpty() }?.let { ": ${it.joinToString()}" } ?: "")
                            else
                                "Disconnected${server.lastError?.let { " — $it" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (server.connected) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Column {
                        TextButton(onClick = {
                            connect(server.url, null)
                        }) { Text("Retry") }
                        TextButton(onClick = {
                            app.mcp.disconnect(server.id)
                            servers = app.mcp.listServers()
                        }) { Text("Remove") }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
