package com.scamkill.app.ui.screens

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.scamkill.app.data.Conversation
import com.scamkill.app.data.SmsLogEntry
import com.scamkill.app.data.SmsMessage
import com.scamkill.app.ui.theme.*
import com.scamkill.app.viewmodel.HomeViewModel
import com.scamkill.app.viewmodel.MessageLogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageLogScreen(
    onNavigateToSettings: () -> Unit,
    messageViewModel: MessageLogViewModel = viewModel(),
    homeViewModel: HomeViewModel = viewModel(),
) {
    val state by messageViewModel.state.collectAsState()

    if (state.selectedAddress != null) {
        ConversationDetailScreen(
            address = state.selectedAddress!!,
            messages = state.messages,
            scamAnnotations = state.scamAnnotations,
            onBack = { messageViewModel.goBack() },
            onSend = { body -> messageViewModel.sendReply(state.selectedAddress!!, body) },
        )
    } else {
        ConversationListScreen(
            conversations = state.conversations,
            isLoading = state.isLoading,
            isScamAddress = { messageViewModel.isScamAddress(it) },
            onSelect = { messageViewModel.selectConversation(it) },
            onSend = { addr, body -> messageViewModel.sendReply(addr, body) },
            onRefresh = { messageViewModel.loadConversations() },
            onNavigateToSettings = onNavigateToSettings,
            homeViewModel = homeViewModel,
        )
    }
}

// ── Conversation List ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListScreen(
    conversations: List<Conversation>,
    isLoading: Boolean,
    isScamAddress: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSettings: () -> Unit,
    homeViewModel: HomeViewModel,
) {
    var showCompose by remember { mutableStateOf(false) }
    val homeState by homeViewModel.state.collectAsState()
    val context = LocalContext.current

    val roleRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { homeViewModel.refresh() }

    val defaultSmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { homeViewModel.refresh() }

    LaunchedEffect(Unit) { homeViewModel.refresh() }

    val allActive = homeState.callScreeningActive && homeState.backendConnected && homeState.isDefaultSmsApp
    val needsSetup = !allActive

    // Pulsing shield color when there are blocked messages
    val hasBlocked = homeState.smsBlockedToday > 0
    val shieldColor by animateColorAsState(
        targetValue = when {
            !allActive -> DangerOrange
            hasBlocked -> ScamRed
            else -> SafeGreen
        },
        label = "shieldColor",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = shieldColor,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "SCAMSTOP",
                            style = MaterialTheme.typography.titleLarge,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    if (homeState.smsBlockedToday > 0) {
                        Text(
                            "${homeState.smsBlockedToday} blocked",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ScamRed,
                            modifier = Modifier
                                .background(ScamRed.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    } else if (homeState.smsAnalyzedToday > 0) {
                        Text(
                            "${homeState.smsAnalyzedToday} scanned",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SafeGreen,
                            modifier = Modifier
                                .background(SafeGreen.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCompose = true },
                containerColor = AccentBlue,
                contentColor = TextPrimary,
            ) {
                Icon(Icons.Default.Create, contentDescription = "New message")
            }
        },
        containerColor = Background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (needsSetup) {
                item(key = "setup") {
                    SetupBanner(
                        homeState = homeState,
                        onEnableCallScreening = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val rm = context.getSystemService(RoleManager::class.java)
                                if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                                    roleRequestLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                                }
                            }
                        },
                        onSetDefaultSms = {
                            try {
                                var launched = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val rm = context.getSystemService(RoleManager::class.java)
                                    if (rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                                        defaultSmsLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                                        launched = true
                                    }
                                }
                                if (!launched) {
                                    @Suppress("DEPRECATION")
                                    val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                                        putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                                    }
                                    defaultSmsLauncher.launch(intent)
                                }
                            } catch (e: Exception) {
                                Log.e("ScamStop", "Failed to launch default SMS picker", e)
                            }
                        },
                    )
                }
            }

            if (isLoading && conversations.isEmpty()) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading...", color = TextDim, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else if (conversations.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No conversations yet", color = TextDim, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                items(conversations, key = { it.address }) { conv ->
                    ConversationItem(
                        conversation = conv,
                        isScam = isScamAddress(conv.address),
                        onClick = { onSelect(conv.address) },
                    )
                    Divider(color = Border, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (showCompose) {
        ComposeMessageDialog(
            onDismiss = { showCompose = false },
            onSend = { addr, body ->
                onSend(addr, body)
                showCompose = false
                onRefresh()
            },
        )
    }
}

// ── Setup Banner ─────────────────────────────────────────────────────────────

@Composable
private fun SetupBanner(
    homeState: com.scamkill.app.viewmodel.HomeUiState,
    onEnableCallScreening: () -> Unit,
    onSetDefaultSms: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!homeState.backendConnected) ScamRed.copy(alpha = 0.1f) else DangerOrange.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (!homeState.backendConnected) ScamRed else DangerOrange,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "SETUP NEEDED",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (!homeState.backendConnected) ScamRed else DangerOrange,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusPill("Backend", homeState.backendConnected)
                StatusPill("Calls", homeState.callScreeningActive)
                StatusPill("SMS", homeState.isDefaultSmsApp)
            }

            if (!homeState.callScreeningActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onEnableCallScreening,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue),
                ) {
                    Text("Enable Call Screening", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (!homeState.isDefaultSmsApp) {
                Spacer(Modifier.height(if (homeState.callScreeningActive) 8.dp else 4.dp))
                OutlinedButton(
                    onClick = onSetDefaultSms,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerOrange),
                ) {
                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Set as Default SMS App", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean) {
    val color = if (active) SafeGreen else ScamRed
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

// ── Conversation Item ────────────────────────────────────────────────────────

@Composable
private fun ConversationItem(
    conversation: Conversation,
    isScam: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (isScam) ScamRed.copy(alpha = 0.2f) else AccentBlue.copy(alpha = 0.15f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isScam) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = ScamRed, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    conversation.displayName.firstOrNull()?.uppercase() ?: "#",
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue,
                    fontSize = 18.sp,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    conversation.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatTimestamp(conversation.lastTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.unreadCount > 0) AccentBlue else TextDim,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    conversation.lastBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(AccentBlue, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${conversation.unreadCount}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                    }
                }
            }
        }
    }
}

// ── Conversation Detail ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationDetailScreen(
    address: String,
    messages: List<SmsMessage>,
    scamAnnotations: Map<Long, SmsLogEntry>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    var replyText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var expandedScamId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(address, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        bottomBar = {
            Surface(
                color = Surface,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        placeholder = { Text("Message", color = TextDim) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = Border,
                            cursorColor = AccentBlue,
                            focusedContainerColor = SurfaceVariant,
                            unfocusedContainerColor = SurfaceVariant,
                        ),
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (replyText.isNotBlank()) {
                                onSend(replyText.trim())
                                replyText = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (replyText.isNotBlank()) AccentBlue else Border,
                                CircleShape
                            ),
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = TextPrimary,
                        )
                    }
                }
            }
        },
        containerColor = Background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            reverseLayout = true,
        ) {
            items(messages, key = { it.id }) { msg ->
                val isSent = msg.type == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT
                val scamEntry = scamAnnotations[msg.id]
                MessageBubble(
                    message = msg,
                    isSent = isSent,
                    scamEntry = scamEntry,
                    isExpanded = expandedScamId == msg.id,
                    onToggleExpand = {
                        expandedScamId = if (expandedScamId == msg.id) null else msg.id
                    },
                )
            }
        }
    }
}

// ── Message Bubble ───────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: SmsMessage,
    isSent: Boolean,
    scamEntry: SmsLogEntry?,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
) {
    val context = LocalContext.current
    val alignment = if (isSent) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = when {
        scamEntry != null -> ScamRed.copy(alpha = 0.15f)
        isSent -> AccentBlue.copy(alpha = 0.3f)
        else -> SurfaceVariant
    }
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isSent) 16.dp else 4.dp,
        bottomEnd = if (isSent) 4.dp else 16.dp,
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(bgColor, shape)
                .then(
                    if (scamEntry != null) Modifier.clickable(onClick = onToggleExpand) else Modifier
                )
                .padding(10.dp),
        ) {
            if (scamEntry != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = ScamRed,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${scamEntry.verdict} (${scamEntry.score})",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScamRed,
                    )
                }
                if (scamEntry.reason.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        scamEntry.reason,
                        fontSize = 11.sp,
                        color = ScamRed.copy(alpha = 0.8f),
                        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isExpanded && scamEntry.keywords.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        scamEntry.keywords.joinToString(" · "),
                        fontSize = 9.sp,
                        color = ScamRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(3.dp))
            }

            val imageUri = message.mmsImageUri ?: scamEntry?.imageUri
            if (imageUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(imageUri))
                        .crossfade(true)
                        .build(),
                    contentDescription = "MMS image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.FillWidth,
                )
                if (message.body.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (message.body.isNotBlank()) {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
            } else if (message.isMms && message.mmsImageUri == null) {
                Text(
                    "(MMS)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                )
            }

            Spacer(Modifier.height(2.dp))
            Text(
                formatTimestamp(message.timestamp),
                fontSize = 10.sp,
                color = TextDim,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

// ── Compose Dialog ───────────────────────────────────────────────────────────

@Composable
private fun ComposeMessageDialog(
    onDismiss: () -> Unit,
    onSend: (String, String) -> Unit,
) {
    var to by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "New Message",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text("To (phone number)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Border,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextDim,
                        cursorColor = AccentBlue,
                    ),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Border,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextDim,
                        cursorColor = AccentBlue,
                    ),
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", color = TextDim)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (to.isNotBlank() && body.isNotBlank()) {
                                onSend(to.trim(), body.trim())
                            }
                        },
                        enabled = to.isNotBlank() && body.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    ) {
                        Text("SEND")
                    }
                }
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun formatTimestamp(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return if (diff < 24 * 60 * 60 * 1000) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
