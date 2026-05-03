package com.mediawalkie.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediawalkie.data.api.Group
import com.mediawalkie.data.api.WalkieApi
import com.mediawalkie.routing.RoutingManager
import com.mediawalkie.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    routingManager: RoutingManager? = null, 
    userName: String = "User", 
    userId: String = "",
    api: WalkieApi? = null
) {
    var isPressed by remember { mutableStateOf(false) }
    var frequency by remember { mutableStateOf("104.5") }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    
    // UI State for the profile fields
    var nameInput by remember { mutableStateOf(userName) }
    var emailInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }

    // Auto-start radio and FETCH GROUPS
    LaunchedEffect(frequency, userId) {
        if (userId.isNotEmpty()) {
            routingManager?.start(frequency, userId)
            try {
                if (api != null) {
                    groups = api.getGroups(userId)
                }
            } catch (e: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundOLED)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Status Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(SuccessGreen)
                    .shadow(4.dp, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "ONLINE",
                color = TextGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { 
                routingManager?.restart(frequency, userId)
            }) {
                Text("RESTART", color = GoldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        // App Title
        Text(
            text = "MEDIA WALKIE",
            color = GoldPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 4.sp,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))

        // Frequency Card (The Glow Card)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                .background(SurfaceCard, RoundedCornerShape(32.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "CHANNEL",
                    color = TextGray,
                    fontSize = 14.sp,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = frequency,
                        color = GoldPrimary,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-2).sp,
                        style = TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = GoldGlow.copy(alpha = 0.5f),
                                blurRadius = 30f
                            )
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "MHz",
                        color = TextGray,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Divider(color = GoldPrimary.copy(alpha = 0.5f), thickness = 1.dp)
        Spacer(Modifier.height(32.dp))

        // Profile / Registration Section (Minimalist Underline style)
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileField("Your Name", nameInput, Modifier.weight(1f)) { nameInput = it }
                ProfileField("Email Address", emailInput, Modifier.weight(1f)) { emailInput = it }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProfileField("Phone Number", phoneInput, Modifier.weight(1f)) { phoneInput = it }
                Spacer(Modifier.width(16.dp))
                TextButton(
                    onClick = { /* Login Logic */ },
                    modifier = Modifier.background(Color.White, RoundedCornerShape(4.dp))
                ) {
                    Text("REGISTER / LOGIN", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // PTT Button (Big Circle)
        Box(
            modifier = Modifier
                .size(220.dp)
                .border(8.dp, if (isPressed) GoldPrimary else SurfaceCard, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(if (isPressed) GoldPrimary.copy(alpha = 0.2f) else BackgroundOLED)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            routingManager?.setPttActive(true, nameInput)
                            tryAwaitRelease()
                            isPressed = false
                            routingManager?.setPttActive(false, nameInput)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "HOLD TO TALK",
                color = if (isPressed) GoldPrimary else TextGray,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(32.dp))

        // Bottom Dynamic Channel Dock
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            item {
                ChannelButton("DEF") { 
                    frequency = "104.5" 
                    routingManager?.start(frequency, userId)
                }
                Spacer(Modifier.width(12.dp))
            }
            
            items(groups) { group ->
                ChannelButton(group.name.take(3).uppercase()) { 
                    frequency = group.frequency 
                    routingManager?.start(frequency, userId)
                }
                Spacer(Modifier.width(12.dp))
            }

            item {
                ChannelButton("+") { /* Add Logic or Refresh */ }
            }
        }
    }
}

@Composable
fun ProfileField(label: String, value: String, modifier: Modifier = Modifier, onValueChange: (String) -> Unit) {
    Column(modifier = modifier) {
        Text(label, color = TextGray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            cursorBrush = SolidColor(GoldPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(0.dp, Color.Transparent)
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val y = size.height - strokeWidth / 2
                    drawLine(TextGray.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = strokeWidth)
                }
        )
    }
}

@Composable
fun ChannelButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .size(width = 70.dp, height = 50.dp)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

