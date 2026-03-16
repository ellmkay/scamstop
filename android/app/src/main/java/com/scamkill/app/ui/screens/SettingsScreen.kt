package com.scamkill.app.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scamkill.app.service.CallForwardingHelper
import com.scamkill.app.ui.theme.*
import com.scamkill.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var newWhitelistNumber by remember { mutableStateOf("") }
    var editingUrl by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf(state.backendUrl) }
    val context = LocalContext.current

    val smsRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.loadSettings() }

    val callRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.loadSettings() }

    LaunchedEffect(state.backendUrl) {
        if (!editingUrl) urlText = state.backendUrl
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SETTINGS", letterSpacing = 2.sp) },
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
        containerColor = Background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // 1. Screening toggles
            item {
                SectionHeader("SCREENING")
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column {
                        SettingsSwitch(
                            label = "Call screening",
                            description = "Reject unknown callers and forward to ScamStop",
                            checked = state.callScreeningEnabled,
                            onCheckedChange = { viewModel.setCallScreeningEnabled(it) },
                        )
                        Divider(color = Border)
                        SettingsSwitch(
                            label = "SMS screening",
                            description = "Analyze incoming SMS for scam patterns",
                            checked = state.smsScreeningEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setSmsScreeningEnabled(enabled)
                                if (!enabled && state.isDefaultSmsApp) {
                                    launchSmsRolePicker(context, smsRoleLauncher)
                                }
                            },
                        )
                    }
                }
            }

            // 2. SMS Threshold
            item {
                SectionHeader("SMS THRESHOLD")
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Block SMS with score >=", style = MaterialTheme.typography.bodyMedium, color = TextDim)
                            Text(
                                "${state.smsThreshold}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    state.smsThreshold > 79 -> ScamRed
                                    state.smsThreshold > 50 -> DangerOrange
                                    else -> WarnYellow
                                },
                            )
                        }
                        Slider(
                            value = state.smsThreshold.toFloat(),
                            onValueChange = { viewModel.setSmsThreshold(it.toInt()) },
                            valueRange = 30f..100f,
                            steps = 13,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentBlue,
                                activeTrackColor = AccentBlue,
                            ),
                        )
                    }
                }
            }

            // 3. Default Apps
            item {
                SectionHeader("DEFAULT APPS")
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // SMS default
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Default SMS App", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (state.isDefaultSmsApp) SafeGreen else ScamRed,
                                                CircleShape,
                                            )
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (state.isDefaultSmsApp) "Active" else "Not set",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (state.isDefaultSmsApp) SafeGreen else ScamRed,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { launchSmsRolePicker(context, smsRoleLauncher) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (state.isDefaultSmsApp) TextDim else DangerOrange,
                                ),
                            ) {
                                Text(
                                    if (state.isDefaultSmsApp) "Change" else "Set",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        Divider(color = Border)

                        // Call screener
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Call Screener", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (state.isCallScreener) SafeGreen else ScamRed,
                                                CircleShape,
                                            )
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (state.isCallScreener) "Active" else "Not set",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (state.isCallScreener) SafeGreen else ScamRed,
                                    )
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val rm = context.getSystemService(RoleManager::class.java)
                                            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                                                callRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                                            }
                                        } catch (e: Exception) {
                                            Log.e("ScamStop", "Failed to launch call screener picker", e)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (state.isCallScreener) TextDim else AccentBlue,
                                    ),
                                ) {
                                    Text(
                                        if (state.isCallScreener) "Change" else "Set",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Call Forwarding
            item {
                CallForwardingSection(viewModel = viewModel)
            }

            // 5. Whitelist
            item {
                SectionHeader("WHITELIST")
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Numbers that always ring through, even if not in contacts",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextDim,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newWhitelistNumber,
                                onValueChange = { newWhitelistNumber = it },
                                placeholder = { Text("+1234567890") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentBlue,
                                    unfocusedBorderColor = Border,
                                    cursorColor = AccentBlue,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    viewModel.addToWhitelist(newWhitelistNumber)
                                    newWhitelistNumber = ""
                                },
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add", tint = SafeGreen)
                            }
                        }
                    }
                }
            }

            items(state.whitelist) { number ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(number, style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { viewModel.removeFromWhitelist(number) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = ScamRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (state.whitelist.isEmpty()) {
                item {
                    Text("No whitelisted numbers", style = MaterialTheme.typography.bodyMedium, color = TextDim)
                }
            }

            // 6. Backend URL (moved to bottom)
            item {
                SectionHeader("BACKEND")
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it; editingUrl = true },
                            label = { Text("Backend URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = Border,
                                cursorColor = AccentBlue,
                            ),
                        )
                        if (editingUrl && urlText != state.backendUrl) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    viewModel.setBackendUrl(urlText)
                                    editingUrl = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text("SAVE")
                            }
                        }
                    }
                }
            }

            // 7. Test scam detection
            item {
                SectionHeader("TEST SCAM DETECTION")
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Send a fake MMS with a scam image to the backend and write the result to the message inbox.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextDim,
                        )

                        Button(
                            onClick = {
                                val imageUrl = (state.backendUrl.trimEnd('/')) + "/scam-test-image.png"
                                viewModel.runScamTest(imageUrl)
                            },
                            enabled = !state.testBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerOrange),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (state.testBusy) "Analyzing..." else "Send test scam MMS",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        if (state.testResult.isNotBlank()) {
                            Text(
                                state.testResult,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                maxLines = 8,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun CallForwardingSection(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var editingNumber by remember { mutableStateOf(false) }
    var numberText by remember(state.forwardingNumber) { mutableStateOf(state.forwardingNumber) }
    var showManualCodes by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled by retry from user */ }

    val callFallbackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.markForwardingActive()
    }

    val deactivateFallbackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.markForwardingInactive()
    }

    fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun doActivate() {
        if (!hasCallPermission()) {
            permissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }
        viewModel.activateForwarding {
            try {
                val intent = CallForwardingHelper.buildActivateIntent(state.forwardingNumber.trim())
                callFallbackLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("ScamStop", "Fallback activate intent failed", e)
            }
        }
    }

    fun doDeactivate() {
        if (!hasCallPermission()) {
            permissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }
        viewModel.deactivateForwarding {
            try {
                deactivateFallbackLauncher.launch(CallForwardingHelper.buildDeactivateIntent())
            } catch (e: Exception) {
                Log.e("ScamStop", "Fallback deactivate intent failed", e)
            }
        }
    }

    SectionHeader("CALL FORWARDING")
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Forward rejected calls to your Twilio number so the AI can answer them. " +
                    "Uses GSM code **67* (forward on busy).",
                style = MaterialTheme.typography.bodyMedium,
                color = TextDim,
            )

            OutlinedTextField(
                value = numberText,
                onValueChange = { numberText = it; editingNumber = true },
                label = { Text("Forwarding number") },
                placeholder = { Text("+46701234567") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = Border,
                    cursorColor = AccentBlue,
                ),
            )

            if (editingNumber && numberText != state.forwardingNumber) {
                Button(
                    onClick = {
                        viewModel.setForwardingNumber(numberText.trim())
                        editingNumber = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("SAVE NUMBER")
                }
            }

            // Status indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (state.forwardingActive) SafeGreen else TextDim,
                            CircleShape,
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.forwardingActive) "Forwarding active" else "Forwarding inactive",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.forwardingActive) SafeGreen else TextDim,
                )
            }

            // Status message from USSD response
            if (state.forwardingStatus.isNotBlank()) {
                Text(
                    state.forwardingStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    maxLines = 6,
                )
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!state.forwardingActive) {
                    Button(
                        onClick = { doActivate() },
                        enabled = state.forwardingNumber.isNotBlank() && !state.forwardingBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PhoneForwarded, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Activate", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = { doDeactivate() },
                        enabled = !state.forwardingBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = ScamRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PhoneDisabled, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Deactivate", fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = {
                        if (!hasCallPermission()) {
                            permissionLauncher.launch(Manifest.permission.CALL_PHONE)
                        } else {
                            viewModel.checkForwardingStatus()
                        }
                    },
                    enabled = !state.forwardingBusy,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue),
                ) {
                    Text("Check", fontWeight = FontWeight.SemiBold)
                }
            }

            if (state.forwardingBusy) {
                Text("Please wait...", fontSize = 12.sp, color = TextDim)
            }

            // Manual dial codes fallback
            TextButton(onClick = { showManualCodes = !showManualCodes }) {
                Text(
                    if (showManualCodes) "Hide dial codes" else "Not working? Show dial codes",
                    fontSize = 12.sp,
                    color = AccentBlue,
                )
            }

            if (showManualCodes) {
                val num = state.forwardingNumber.ifBlank { "<number>" }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Tap a code to copy it, then paste in your dialer:", fontSize = 12.sp, color = TextDim)
                    Spacer(Modifier.height(4.dp))
                    DialCodeRow("Activate", CallForwardingHelper.activateCode(num))
                    DialCodeRow("Deactivate", CallForwardingHelper.deactivateCode())
                    DialCodeRow("Check status", CallForwardingHelper.checkCode())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "You can also check in: Settings > Network > Call forwarding",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialCodeRow(label: String, code: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = TextDim)
        Text(
            code,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = AccentBlue,
            modifier = Modifier
                .background(AccentBlue.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                .clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("dial code", code))
                    Toast.makeText(context, "Copied! Paste in your dialer", Toast.LENGTH_SHORT).show()
                    try {
                        context.startActivity(Intent(Intent.ACTION_DIAL))
                    } catch (_: Exception) { }
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun launchSmsRolePicker(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                return
            }
        }
        @Suppress("DEPRECATION")
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
        launcher.launch(intent)
    } catch (e: Exception) {
        Log.e("ScamStop", "Failed to launch SMS role picker", e)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = TextDim,
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsSwitch(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = TextDim)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SafeGreen,
                checkedTrackColor = SafeGreen.copy(alpha = 0.3f),
            ),
        )
    }
}
