package com.omnihub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnihub.OmniHubApp
import com.omnihub.terminal.TerminalLog
import com.omnihub.ui.theme.OmniAmber
import com.omnihub.ui.theme.OmniBg
import com.omnihub.ui.theme.OmniSurface
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OmniHub Terminal — every command executed on this device in one scrollback:
 * typed here by the user, run by the AI through its tools, or proxied from an
 * outside MCP client via the device tunnel. Amber-on-black, monospace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val app = OmniHubApp.instance
    val scope = rememberCoroutineScope()
    val entries by TerminalLog.entries.collectAsState()
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    suspend fun runCommand(cmd: String) {
        if (cmd.isBlank() || running) return
        input = ""
        running = true
        try {
            app.terminal.run(cmd, origin = TerminalLog.Origin.USER)
        } finally {
            running = false
        }
    }

    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, null, tint = OmniAmber)
                        Spacer(Modifier.width(8.dp))
                        Text("Terminal", color = OmniAmber, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = OmniAmber)
                    }
                },
                actions = {
                    IconButton(onClick = { TerminalLog.clear() }) {
                        Icon(Icons.Default.Delete, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmniBg)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Scrollback
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(OmniBg)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (entries.isEmpty()) {
                    item {
                        Column(Modifier.padding(24.dp)) {
                            Text(
                                "omni ~ $",
                                fontFamily = FontFamily.Monospace,
                                color = OmniAmber,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Commands run on this device — by you, by the AI, or by an MCP client through the tunnel — appear here.",
                                color = Color(0xFF8A8A93),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                items(entries, key = { it.id }) { e ->
                    TerminalEntry(e)
                }
            }

            // Input line
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(OmniSurface)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$",
                    color = OmniAmber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("shell command — try ls, git status, cat …", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF6A6A72)) },
                    textStyle = LocalTextStyle.current.copy(
                        color = Color(0xFFEAEAEF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OmniAmber,
                        unfocusedBorderColor = Color(0xFF33333B),
                        cursorColor = OmniAmber
                    )
                )
                IconButton(
                    onClick = {
                        val cmd = input
                        scope.launch { runCommand(cmd) }
                    },
                    enabled = !running && input.isNotBlank()
                ) {
                    if (running) {
                        CircularProgressIndicator(color = OmniAmber, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Send, "Run", tint = if (input.isNotBlank()) OmniAmber else Color(0xFF55555C))
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalEntry(e: TerminalLog.Entry) {
    val ts = remember(e.ts) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.ts))
    }
    val originLabel = when (e.origin) {
        TerminalLog.Origin.USER -> "you"
        TerminalLog.Origin.AI -> "ai"
        TerminalLog.Origin.TUNNEL -> "tunnel"
    }
    val originColor = when (e.origin) {
        TerminalLog.Origin.USER -> Color(0xFF8A8A93)
        TerminalLog.Origin.AI -> OmniAmber
        TerminalLog.Origin.TUNNEL -> Color(0xFF6FC3FF)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$ts $originLabel",
                color = originColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "$ ${e.command}",
            color = Color(0xFFEAEAEF),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
        if (e.output.isNotBlank()) {
            Text(
                e.output.take(4000),
                color = if (e.exitCode == 0) Color(0xFF9BE29B) else Color(0xFFE2A29B),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
