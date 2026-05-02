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
import com.mediawalkie.routing.RoutingManager
import com.mediawalkie.ui.theme.PrimaryVibrant

@Composable
fun MainScreen(routingManager: RoutingManager? = null) {
    var isPressed by remember { mutableStateOf(false) }
    var frequency by remember { mutableStateOf("104.5") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Frequency Tuner UI
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "FREQUENCY",
                color = Color.Gray,
                fontSize = 14.sp,
                letterSpacing = 2.sp
            )
            Text(
                text = frequency,
                color = PrimaryVibrant,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "MHz",
                color = Color.Gray,
                fontSize = 18.sp
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
        Text(
            text = "STATUS: SCANNING (OFFLINE MESH)",
            color = Color.Gray,
            fontSize = 12.sp,
            letterSpacing = 1.sp
        )
    }
}
