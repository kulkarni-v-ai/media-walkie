package com.mediawalkie.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import com.mediawalkie.data.api.Group
import com.mediawalkie.data.api.GroupRequest
import com.mediawalkie.data.api.WalkieApi
import com.mediawalkie.routing.RoutingManager
import com.mediawalkie.ui.theme.PrimaryVibrant
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(routingManager: RoutingManager? = null, userName: String = "Unknown", api: WalkieApi? = null, userId: String = "") {
    var isPressed by remember { mutableStateOf(false) }
    var frequency by remember { mutableStateOf("104.5") }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var showGroupsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Fetch groups on load
    LaunchedEffect(userId) {
        try {
            if (api != null && userId.isNotEmpty()) {
                groups = api.getGroups(userId)
            }
        } catch (e: Exception) {
            // Ignore error for now
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Welcome, $userName",
                color = PrimaryVibrant,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            
            // Speaker Indicator
            val activeSpeaker = routingManager?.currentSpeaker
            if (activeSpeaker != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$activeSpeaker is talking...",
                        color = Color.Red,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Frequency Tuner UI
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "FREQUENCY / GROUP",
                color = Color.Gray,
                fontSize = 14.sp,
                letterSpacing = 2.sp
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { showGroupsDialog = true })
                }
            ) {
                Text(
                    text = frequency,
                    color = PrimaryVibrant,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Select Group", tint = PrimaryVibrant)
            }
            Text(
                text = "MHz",
                color = Color.Gray,
                fontSize = 18.sp
            )
        }

        if (showGroupsDialog) {
            var showAddDialog by remember { mutableStateOf(false) }
            var newName by remember { mutableStateOf("") }
            var newFreq by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showGroupsDialog = false },
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Select Channel")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, "Add")
                            Text("New")
                        }
                    }
                },
                text = {
                    Column {
                        if (showAddDialog) {
                            Column(Modifier.padding(bottom = 16.dp)) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    label = { Text("Channel Name") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = newFreq,
                                    onValueChange = { newFreq = it },
                                    label = { Text("Frequency (MHz)") },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                                    TextButton(onClick = {
                                        if (newName.isNotBlank() && newFreq.isNotBlank()) {
                                            scope.launch {
                                                try {
                                                    api?.createGroup(GroupRequest(newName, newFreq))
                                                    groups = api?.getGroups(userId) ?: emptyList()
                                                    showAddDialog = false
                                                } catch (e: Exception) {}
                                            }
                                        }
                                    }) { Text("Create") }
                                }
                                androidx.compose.material3.Divider(modifier = Modifier.padding(vertical = 16.dp), color = Color.Gray.copy(alpha = 0.2f))
                            }
                        }

                        LazyColumn {
                            item {
                                TextButton(onClick = { 
                                    frequency = "104.5"
                                    routingManager?.start(frequency, userId)
                                    showGroupsDialog = false
                                }) { Text("Default (104.5 MHz)") }
                            }
                            item {
                                TextButton(onClick = { 
                                    frequency = "None"
                                    routingManager?.stop()
                                    showGroupsDialog = false
                                }) { 
                                    Text("None (Muted)", color = Color.Red) 
                                }
                            }
                            items(groups) { group ->
                                TextButton(onClick = { 
                                    frequency = group.frequency
                                    routingManager?.start(frequency, userId)
                                    showGroupsDialog = false
                                }) {
                                    Text("${group.name} (${group.frequency} MHz)")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGroupsDialog = false }) { Text("Close") }
                }
            )
        }

        // PTT Button
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(if (isPressed) PrimaryVibrant else MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            routingManager?.setPttActive(true, userName)
                            tryAwaitRelease()
                            isPressed = false
                            routingManager?.setPttActive(false, userName)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPressed) "TRANSMITTING" else "PUSH TO TALK",
                color = if (isPressed) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        // Network Status
        val offlinePeers = routingManager?.connectedMeshPeers ?: 0
        val onlinePeers = routingManager?.connectedOnlineUsers ?: 0
        val totalPeers = offlinePeers + onlinePeers

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (totalPeers > 0) "STATUS: ACTIVE" else "STATUS: SCANNING...",
                    color = if (totalPeers > 0) Color(0xFF4CAF50) else Color.Gray,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { 
                    routingManager?.start(frequency, userId)
                }) {
                    Text("RESTART RADIO", fontSize = 10.sp)
                }
            }
            Text(
                text = "($offlinePeers MESH | $onlinePeers ONLINE)",
                color = Color.Gray.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
