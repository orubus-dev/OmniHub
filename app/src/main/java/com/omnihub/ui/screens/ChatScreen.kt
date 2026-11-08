package com.omnihub.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnihub.OmniHubApp
import com.omnihub.data.UserPrefs
import com.omnihub.history.ConversationEntity
import com.omnihub.providers.ChatMessage
import com.omnihub.source.SourceRouter
import com.omnihub.source.SourceHealth
import com.omnihub.source.core.ProviderAuthStore
import com.omnihub.source.core.WarmSessionPool
import com.omnihub.tools.ToolLoopEngine
import com.omnihub.update.AppUpdateChecker
import com.omnihub.update.AppUpdateInfo
import com.omnihub.ui.theme.OmniAmber
import com.omnihub.ui.theme.OmniGlassBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class ChatBubble(val role: String, val content: String)

private val GREETINGS = listOf("Ready when you are.", "What are we building?", "Let's continue.", "Pick up where you left off.", "Your move.")

private fun timeGreeting(name: String): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val base = when {
        hour < 5 -> "Still up"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        hour < 21 -> "Good evening"
        else -> "Late night"
    }
    val head = if (name.isBlank()) "$base." else "$base, $name."
    return "$head\n${GREETINGS[hour % GREETINGS.size]}"
}

/** Relay/engine failures must never look like AI prose. */
private fun looksLikeError(t: String): Boolean = listOf(
    "REPLY_TIMEOUT", "AUTH_REQUIRED", "COLD_LOAD_TIMEOUT", "SEND_FAILED",
    "NO_COMPOSER", "INPUT_FAILED", "SEND_CLICK_FAILED", "Not signed in",
    "No source installed", "All providers failed", "cooling down",
    "relay failed", "empty reply"
).any { t.contains(it, ignoreCase = true) }

@Composable
private fun BounceDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val y by t.animateFloat(
                initialValue = 0f,
                targetValue = -7f,
                animationSpec = infiniteRepeatable(
                    animation = tween(380, delayMillis = i * 120),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                Modifier
                    .offset(y = y.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(OmniAmber)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenCustomize: () -> Unit,
    onOpenSkills: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    onOpenStore: () -> Unit = {},
    onOpenConnectors: () -> Unit = {},
    onOpenTerminal: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniHubApp
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val keyboard = LocalSoftwareKeyboardController.current
    val convPrefs = remember { context.getSharedPreferences("omni_chat_session", 0) }
    var currentConvId by remember { mutableStateOf(convPrefs.getString("active_conv_id", null)) }
    var messages by remember { mutableStateOf(listOf<ChatBubble>()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var answering by remember { mutableStateOf(false) }
    var conversations by remember { mutableStateOf(listOf<ConversationEntity>()) }
    var projects by remember { mutableStateOf(listOf<com.omnihub.history.ProjectEntity>()) }
    var incognito by remember { mutableStateOf(UserPrefs.isIncognito(context)) }
    var greeting by remember { mutableStateOf(timeGreeting(UserPrefs.getName(context))) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showProviderSheet by remember { mutableStateOf(false) }
    var pendingAttachments by remember { mutableStateOf(listOf<String>()) }
    var preferred by remember { mutableStateOf(ProviderAuthStore.preferredProvider(context)) }
    var menuConv by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    val listState = rememberLazyListState()
    val sources by app.sourceManager.sources.collectAsState()
    var uiLocked by remember { mutableStateOf(false) }

    // Live-reply metadata: who answered, how fast, and a cancellable job
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var replySource by remember { mutableStateOf("") }
    var replyMs by remember { mutableStateOf(0L) }
    var elapsedSec by remember { mutableStateOf(0) }
    // Tool activity of the in-flight reply (⚙️ started / ✓ finished lines)
    var toolTrace by remember { mutableStateOf(listOf<String>()) }
    var replyToolCalls by remember { mutableStateOf(0) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) pendingAttachments = pendingAttachments + uris.map { it.toString() }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) pendingAttachments = pendingAttachments + uris.map { it.toString() }
    }

    fun persistActive(id: String?) {
        currentConvId = id
        convPrefs.edit().putString("active_conv_id", id).apply()
    }

    suspend fun loadMessagesFromDb(id: String?) {
        if (uiLocked || sending) return
        if (id == null || incognito) { messages = emptyList(); return }
        val list = withContext(Dispatchers.IO) { app.chatRepo.getMessages(id) }
        if (uiLocked || sending) return
        if (id != currentConvId) return
        messages = list.map { ChatBubble(it.role, it.content) }
    }

    fun openConversation(id: String) {
        if (incognito || sending || uiLocked) return
        persistActive(id)
        scope.launch { loadMessagesFromDb(id); drawerState.close() }
    }

    fun startNewChat() {
        if (sending) return
        messages = emptyList()
        persistActive(null)
        pendingAttachments = emptyList()
        replySource = ""
        replyMs = 0
        greeting = timeGreeting(UserPrefs.getName(context))
    }

    fun toggleIncognito() {
        if (sending) return
        incognito = !incognito
        UserPrefs.setIncognito(context, incognito)
        if (incognito) startNewChat()
    }

    fun providerLabel(): String {
        if (preferred == "auto") return "Auto"
        return sources.find { it.info.id == preferred }?.info?.name ?: preferred
    }

    fun warmProvider(id: String) {
        val src = sources.find { it.info.id == id } ?: return
        // duck.ai's relay lane lives under a different pool key than its source id
        val poolId = if (id == "duck_ai") "duckduckgo_ai" else id
        WarmSessionPool.warm(context, poolId, src.info.websiteUrl)
    }

    LaunchedEffect(Unit) {
        greeting = timeGreeting(UserPrefs.getName(context))
        app.sourceManager.reload()
        loadMessagesFromDb(currentConvId)
        launch { app.chatRepo.observeConversations().collect { conversations = it } }
        launch { app.chatRepo.observeProjects().collect { projects = it } }
        launch { updateInfo = runCatching { AppUpdateChecker.checkOmniHub(context) }.getOrNull() }
        if (preferred != "auto") warmProvider(preferred)
    }

    // Live stopwatch while a reply is streaming
    LaunchedEffect(sending) {
        if (sending) {
            elapsedSec = 0
            while (sending) {
                delay(1000)
                elapsedSec++
            }
        }
    }

    fun send() {
        val text = input.trim()
        if ((text.isBlank() && pendingAttachments.isEmpty()) || sending) return
        val attachNote = if (pendingAttachments.isNotEmpty()) "\n\n[Attached: ${pendingAttachments.size} item(s)]" else ""
        val userText = text + attachNote
        input = ""
        pendingAttachments = emptyList()
        keyboard?.hide()
        sending = true
        answering = false
        uiLocked = true
        replySource = ""
        replyMs = 0
        replyToolCalls = 0
        toolTrace = emptyList()
        val baseline = messages
        messages = baseline + ChatBubble("user", userText) + ChatBubble("assistant", "")

        sendJob = scope.launch {
            var convId = currentConvId
            try {
                if (!incognito) {
                    if (convId == null) {
                        convId = withContext(Dispatchers.IO) {
                            app.chatRepo.createConversation(userText.take(40).ifBlank { "Chat" })
                        }
                        currentConvId = convId
                        convPrefs.edit().putString("active_conv_id", convId).apply()
                    }
                    withContext(Dispatchers.IO) { app.chatRepo.addMessage(convId!!, "user", userText) }
                }

                val hist = (baseline + ChatBubble("user", userText)).map { ChatMessage(it.role, it.content) }

                var finalText = ""
                app.toolLoop.run(
                    messages = hist,
                    preferredSourceId = if (preferred == "auto") null else preferred,
                    conversationId = convId
                ).collect { ev ->
                    when (ev) {
                        is ToolLoopEngine.LoopEvent.Delta -> {
                            answering = true
                            // Cumulative snapshot — REPLACE the live bubble
                            messages = baseline + ChatBubble("user", userText) + ChatBubble("assistant", ev.text)
                        }
                        is ToolLoopEngine.LoopEvent.ToolStarted -> {
                            toolTrace = toolTrace + "⚙️ ${ev.tool}(${ev.argsPreview})"
                        }
                        is ToolLoopEngine.LoopEvent.ToolFinished -> {
                            val mark = if (ev.ok) "✓" else "✗"
                            toolTrace = toolTrace + "$mark ${ev.tool}: ${ev.outputPreview.take(120)}"
                        }
                        is ToolLoopEngine.LoopEvent.Done -> {
                            finalText = ev.content
                            replySource = ev.sourceName
                            replyMs = ev.elapsedMs
                            replyToolCalls = ev.toolCalls
                        }
                    }
                }

                val reply = finalText.ifBlank { "No reply." }
                messages = baseline + ChatBubble("user", userText) + ChatBubble("assistant", reply)
                if (!incognito && convId != null) {
                    withContext(Dispatchers.IO) { app.chatRepo.addMessage(convId, "assistant", reply) }
                }
            } catch (e: CancellationException) {
                val partial = messages.lastOrNull()?.content.orEmpty()
                if (partial.isBlank()) {
                    messages = messages.dropLast(1) + ChatBubble("assistant", "Stopped.")
                }
                replySource = ""
            } catch (e: Exception) {
                val err = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
                messages = baseline + ChatBubble("user", userText) + ChatBubble("assistant", err)
                if (!incognito && convId != null) {
                    runCatching { withContext(Dispatchers.IO) { app.chatRepo.addMessage(convId, "assistant", err) } }
                }
            } finally {
                sending = false
                answering = false
                uiLocked = false
                sendJob = null
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("OmniHub", Modifier.padding(16.dp), fontWeight = FontWeight.Bold, color = OmniAmber)
                NavigationDrawerItem(icon = { Icon(Icons.Default.Add, null, tint = OmniAmber) }, label = { Text("New chat") }, selected = false, onClick = { startNewChat(); scope.launch { drawerState.close() } })
                NavigationDrawerItem(icon = { Icon(Icons.Outlined.Folder, null, tint = OmniAmber) }, label = { Text("Projects") }, selected = false, onClick = { scope.launch { drawerState.close() }; onOpenProjects() })
                NavigationDrawerItem(icon = { Icon(Icons.Outlined.Store, null, tint = OmniAmber) }, label = { Text("Store") }, selected = false, onClick = { scope.launch { drawerState.close() }; onOpenStore() })
                NavigationDrawerItem(icon = { Icon(Icons.Outlined.Extension, null, tint = OmniAmber) }, label = { Text("Connectors") }, selected = false, onClick = { scope.launch { drawerState.close() }; onOpenConnectors() })
                NavigationDrawerItem(icon = { Icon(Icons.Default.Terminal, null, tint = OmniAmber) }, label = { Text("Terminal") }, selected = false, onClick = { scope.launch { drawerState.close() }; onOpenTerminal() })
                HorizontalDivider(color = OmniGlassBorder, modifier = Modifier.padding(vertical = 8.dp))
                Text("Chats", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(conversations, key = { it.id }) { conv ->
                        Row(
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = { openConversation(conv.id) },
                                onLongClick = { menuConv = conv; renameText = conv.title }
                            ).background(if (conv.id == currentConvId) OmniAmber.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (conv.isPinned) {
                                Icon(Icons.Default.PushPin, null, tint = OmniAmber, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(conv.title.ifBlank { "Chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                HorizontalDivider(color = OmniGlassBorder)
                val name = UserPrefs.getName(context).ifBlank { "You" }
                NavigationDrawerItem(
                    icon = { Box(Modifier.size(28.dp).clip(CircleShape).background(OmniAmber), contentAlignment = Alignment.Center) { Text(UserPrefs.getInitials(context), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) } },
                    label = { Text(name) }, selected = false, onClick = onOpenSettings
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.ime.union(WindowInsets.systemBars),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { if (drawerState.isClosed) drawerState.open() else drawerState.close() } }) { Icon(Icons.Default.Menu, "Menu", tint = OmniAmber) }
                            IconButton(onClick = { toggleIncognito() }) { Icon(Icons.Outlined.VisibilityOff, "Incognito", tint = if (incognito) OmniAmber else MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (incognito) Text("Incognito", style = MaterialTheme.typography.labelMedium, color = OmniAmber)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSources) { Icon(Icons.Outlined.AccountTree, "Providers", tint = OmniAmber) }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings", tint = OmniAmber) }
                    }
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime).padding(8.dp)) {
                    if (pendingAttachments.isNotEmpty()) Text("${pendingAttachments.size} attached", style = MaterialTheme.typography.labelMedium, color = OmniAmber, modifier = Modifier.padding(horizontal = 8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showAddSheet = true }) { Icon(Icons.Default.Add, "Add", tint = OmniAmber) }
                        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text(if (incognito) "Incognito message" else "Message") }, shape = RoundedCornerShape(24.dp), maxLines = 4)
                        if (sending) {
                            // Stop: cancels the relay; partial text stays on screen
                            IconButton(onClick = { sendJob?.cancel() }) {
                                Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(onClick = { send() }) { Icon(Icons.Default.Send, "Send", tint = OmniAmber) }
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                updateInfo?.let { info ->
                    Surface(color = OmniAmber.copy(alpha = 0.2f), modifier = Modifier.fillMaxWidth().clickable { AppUpdateChecker.openReleasePage(context, info) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Update: ${info.tag}", fontWeight = FontWeight.SemiBold, color = OmniAmber)
                                Text(info.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            Text("What's new", color = OmniAmber, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (messages.isEmpty()) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(greeting, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.clickable { showProviderSheet = true }) {
                                Text(providerLabel(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = OmniAmber, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.clickable { showProviderSheet = true }) {
                                    Text(providerLabel(), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = OmniAmber)
                                }
                            }
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                                items(messages.size) { idx ->
                                    val msg = messages[idx]
                                    val mine = msg.role == "user"
                                    val isLiveAssistant = !mine && idx == messages.lastIndex && sending
                                    val isErr = !mine && !isLiveAssistant && looksLikeError(msg.content)
                                    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                                            Surface(
                                                shape = RoundedCornerShape(16.dp),
                                                color = when {
                                                    mine -> OmniAmber.copy(alpha = 0.25f)
                                                    isErr -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                modifier = Modifier.widthIn(max = 320.dp)
                                            ) {
                                                Column(Modifier.padding(12.dp)) {
                                                    when {
                                                        isLiveAssistant && msg.content.isBlank() && !answering -> {
                                                            BounceDots()
                                                        }
                                                        isLiveAssistant && msg.content.isBlank() && answering -> {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                Spacer(Modifier.width(6.dp))
                                                                Text("${elapsedSec}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                            }
                                                        }
                                                        else -> {
                                                            Text(msg.content, style = MaterialTheme.typography.bodyLarge)
                                                        }
                                                    }
                                                    // Live tool activity under the streaming reply
                                                    if (isLiveAssistant && toolTrace.isNotEmpty()) {
                                                        Spacer(Modifier.height(6.dp))
                                                        toolTrace.takeLast(4).forEach { line ->
                                                            Text(
                                                                line,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = OmniAmber,
                                                                modifier = Modifier.padding(vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        // "who answered · how fast" — only on a finished real reply
                                        if (!mine && !sending && idx == messages.lastIndex && replySource.isNotBlank() && !isErr) {
                                            Text(
                                                "via $replySource · ${"%.1f".format(replyMs / 1000f)}s" + if (replyToolCalls > 0) " · ⚙️$replyToolCalls" else "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 2.dp, start = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
                                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            ListItem(headlineContent = { Text("Add image") }, leadingContent = { Icon(Icons.Default.Image, null) }, modifier = Modifier.clickable { showAddSheet = false; imagePicker.launch("image/*") })
            ListItem(headlineContent = { Text("Add file") }, leadingContent = { Icon(Icons.Default.AttachFile, null) }, modifier = Modifier.clickable { showAddSheet = false; filePicker.launch(arrayOf("*/*")) })
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showProviderSheet) {
        ModalBottomSheet(onDismissRequest = { showProviderSheet = false }) {
            ListItem(
                headlineContent = { Text("Auto — best source, instant fallback") },
                supportingContent = { Text("Rides the whole relay chain with memory") },
                leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = OmniAmber) },
                modifier = Modifier.clickable {
                    preferred = "auto"; ProviderAuthStore.setPreferredProvider(context, "auto"); showProviderSheet = false
                }
            )
            sources.forEach { s ->
                val dot = when (s.health()) {
                    SourceHealth.HEALTHY -> OmniAmber
                    SourceHealth.AUTH_REQUIRED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
                ListItem(
                    headlineContent = { Text(s.info.name) },
                    supportingContent = {
                        Text(
                            when (s.health()) {
                                SourceHealth.HEALTHY -> "Signed in · warm relay"
                                SourceHealth.AUTH_REQUIRED -> "Sign in required"
                                SourceHealth.UNKNOWN -> "Guest mode · sign in for your account"
                                else -> s.info.kind.name.lowercase()
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingContent = { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)) },
                    modifier = Modifier.clickable {
                        preferred = s.info.id
                        ProviderAuthStore.setPreferredProvider(context, s.info.id)
                        warmProvider(s.info.id)
                        showProviderSheet = false
                    }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    menuConv?.let { conv ->
        ModalBottomSheet(onDismissRequest = { menuConv = null }) {
            ListItem(headlineContent = { Text("Rename") }, modifier = Modifier.clickable { showRename = true; menuConv = null })
            ListItem(headlineContent = { Text(if (conv.isPinned) "Unpin" else "Pin") }, modifier = Modifier.clickable {
                scope.launch { withContext(Dispatchers.IO) { app.chatRepo.setPinned(conv.id, !conv.isPinned) }; menuConv = null }
            })
            ListItem(headlineContent = { Text("Add to project") }, modifier = Modifier.clickable { showProjectPicker = true })
            ListItem(headlineContent = { Text("Delete") }, modifier = Modifier.clickable {
                scope.launch {
                    withContext(Dispatchers.IO) { app.chatRepo.deleteConversation(conv.id) }
                    if (currentConvId == conv.id) startNewChat()
                    menuConv = null
                }
            })
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        currentConvId?.let { cid ->
                            withContext(Dispatchers.IO) { app.chatRepo.renameConversation(cid, renameText) }
                        }
                        showRename = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}
