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
import kotlinx.coroutines.launch

@Composable
fun MainScreen(routingManager: RoutingManager? = null, userName: String = "Unknown", api: WalkieApi? = null) {
    var isPressed by remember { mutableStateOf(false) }
    var frequency by remember { mutableStateOf("104.5") }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var showGroupsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Fetch groups on load
    LaunchedEffect(Unit) {
        try {
            if (api != null) {
                groups = api.getGroups()
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
        // User Profile Header
        Text(
            text = "Welcome, $userName",
            color = PrimaryVibrant,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start)
        )

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
            AlertDialog(
                onDismissRequest = { showGroupsDialog = false },
                title = { Text("Select Group") },
                text = {
                    LazyColumn {
                        item {
                            TextButton(onClick = { 
                                frequency = "104.5"
                                routingManager?.start(frequency)
                                showGroupsDialog = false
                            }) { Text("Default (104.5 MHz)") }
                        }
                        items(groups) { group ->
                            TextButton(onClick = { 
                                frequency = group.frequency
                                routingManager?.start(frequency)
                                showGroupsDialog = false
                            }) {
                                Text("${group.name} (${group.frequency} MHz)")
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
                            routingManager?.setPttActive(true)
                            tryAwaitRelease()
                            isPressed = false
                            routingManager?.setPttActive(false)
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
        val peerCount = routingManager?.connectedMeshPeers ?: 0
        Text(
            text = if (peerCount > 0) "STATUS: CONNECTED ($peerCount OFFLINE PEERS)" else "STATUS: SCANNING (OFFLINE MESH)",
            color = if (peerCount > 0) Color(0xFF4CAF50) else Color.Gray,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
