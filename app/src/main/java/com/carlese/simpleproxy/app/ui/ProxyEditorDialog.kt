package com.carlese.simpleproxy.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.carlese.simpleproxy.app.data.ProxyProfile
import com.carlese.simpleproxy.app.data.ProxyProtocol
import com.carlese.simpleproxy.app.data.ProxyUriParser
import com.carlese.simpleproxy.app.ui.theme.tvFocusRing
import java.util.UUID

@Composable
fun ProxyEditorDialog(
    initialProfile: ProxyProfile?,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (ProxyProfile) -> Unit,
    onDelete: (String) -> Unit
) {
    val isNew = initialProfile == null
    var uriText by remember { mutableStateOf("") }
    var uriParsed by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var protocol by remember { mutableStateOf(initialProfile?.protocol ?: ProxyProtocol.SOCKS5) }
    var host by remember { mutableStateOf(initialProfile?.host ?: "") }
    var portText by remember { mutableStateOf(initialProfile?.port?.toString() ?: "") }
    var username by remember { mutableStateOf(initialProfile?.username ?: "") }
    var password by remember { mutableStateOf(initialProfile?.password ?: "") }
    var showPass by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .widthIn(max = 480.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isNew) strings.addProxy else strings.editProxy,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.tvFocusRing(CircleShape)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = strings.cancel)
                    }
                }

                OutlinedTextField(
                    value = uriText,
                    onValueChange = { raw ->
                        uriText = raw
                        val parsed = ProxyUriParser.parse(raw)
                        if (parsed != null) {
                            uriParsed = true
                            if (name.isBlank()) name = parsed.name
                            protocol = parsed.protocol
                            host = parsed.host
                            portText = parsed.port.toString()
                            username = parsed.username
                            password = parsed.password
                        } else {
                            uriParsed = false
                        }
                    },
                    label = { Text(strings.quickUriHint) },
                    placeholder = { Text("socks5://proxy.example:1080") },
                    singleLine = true,
                    supportingText = if (uriParsed) {
                        { Text(strings.uriApplied, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )

                var protocolMenuExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(strings.nameLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = strings.protocolLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                        Box {
                            Surface(
                                onClick = { protocolMenuExpanded = true },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .height(56.dp)
                                    .tvFocusRing(RoundedCornerShape(12.dp))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .height(56.dp)
                                        .padding(start = 12.dp, end = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = protocol.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowDropDown,
                                        contentDescription = strings.protocolLabel,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = protocolMenuExpanded,
                                onDismissRequest = { protocolMenuExpanded = false }
                            ) {
                                ProxyProtocol.entries.forEach { item ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = item.name,
                                                fontWeight = if (item == protocol) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        },
                                        trailingIcon = if (item == protocol) {
                                            { Icon(Icons.Rounded.Check, contentDescription = null) }
                                        } else null,
                                        onClick = {
                                            protocol = item
                                            protocolMenuExpanded = false
                                        },
                                        modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(strings.hostLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(0.65f)
                    )
                    val portError = portText.isNotEmpty() && (portText.toIntOrNull()?.let { it !in 1..65535 } ?: true)
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { raw ->
                            portText = raw.filter(Char::isDigit).take(5)
                        },
                        label = { Text(strings.portLabel) },
                        isError = portError,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.35f)
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(strings.userLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.passLabel) },
                    singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { showPass = !showPass },
                            modifier = Modifier.tvFocusRing(CircleShape)
                        ) {
                            Icon(
                                imageVector = if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (initialProfile != null) {
                        TextButton(
                            onClick = {
                                onDelete(initialProfile.id)
                                onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.tvFocusRing(CircleShape)
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.delete)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.tvFocusRing(CircleShape)
                    ) {
                        Text(strings.cancel)
                    }
                    Spacer(Modifier.width(6.dp))
                    val parsedPort = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    Button(
                        enabled = host.isNotBlank() && parsedPort != null,
                        modifier = Modifier.tvFocusRing(CircleShape),
                        onClick = {
                            val cleanHost = host.trim()
                            val validPort = parsedPort ?: return@Button
                            val cleanName = name.trim().ifEmpty { "${protocol.name} · $cleanHost" }
                            onSave(
                                ProxyProfile(
                                    id = initialProfile?.id ?: UUID.randomUUID().toString(),
                                    name = cleanName,
                                    protocol = protocol,
                                    host = cleanHost,
                                    port = validPort,
                                    username = username.trim(),
                                    password = password
                                )
                            )
                            onDismiss()
                        }
                    ) {
                        Text(strings.save)
                    }
                }
            }
        }
    }
}
