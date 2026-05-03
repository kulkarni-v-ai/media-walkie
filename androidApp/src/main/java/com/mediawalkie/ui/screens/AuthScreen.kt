package com.mediawalkie.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediawalkie.data.SessionManager
import com.mediawalkie.data.api.RegisterRequest
import com.mediawalkie.data.api.WalkieApi
import com.mediawalkie.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(sessionManager: SessionManager, api: WalkieApi, onVerified: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundOLED)
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MEDIA WALKIE",
            color = GoldPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 4.sp
        )
        Text(
            text = "AUTHENTICATION",
            color = TextGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Callsign / Name") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GoldPrimary,
                unfocusedBorderColor = TextGray.copy(alpha = 0.3f),
                focusedLabelColor = GoldPrimary,
                unfocusedLabelColor = TextGray,
                cursorColor = GoldPrimary
            )
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone Number") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GoldPrimary,
                unfocusedBorderColor = TextGray.copy(alpha = 0.3f),
                focusedLabelColor = GoldPrimary,
                unfocusedLabelColor = TextGray,
                cursorColor = GoldPrimary
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text("Security PIN") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GoldPrimary,
                unfocusedBorderColor = TextGray.copy(alpha = 0.3f),
                focusedLabelColor = GoldPrimary,
                unfocusedLabelColor = TextGray,
                cursorColor = GoldPrimary
            )
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (errorMsg.isNotEmpty()) {
            Text(
                text = errorMsg, 
                color = if (errorMsg.contains("success", true)) SuccessGreen else Color.Red, 
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val res = api.register(RegisterRequest(name, pin, "device123", "", phone))
                            if (res.error == null) {
                                errorMsg = "Success! Now use your PIN to LOGIN."
                                name = ""
                                phone = ""
                                pin = ""
                            } else {
                                errorMsg = res.error
                            }
                        } catch (e: Exception) {
                            errorMsg = "Network error: ${e.message}"
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.weight(1f).height(56.dp),
                enabled = !isLoading && name.isNotBlank() && pin.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Register", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val res = api.verify(RegisterRequest(name, pin, "device123"))
                            if (res.user?.isVerified == true) {
                                sessionManager.saveSession(res.user.name, true)
                                sessionManager.saveUserId(res.user._id)
                                onVerified()
                            } else {
                                errorMsg = res.error ?: "Account is pending verification by Superadmin."
                            }
                        } catch (e: Exception) {
                            errorMsg = "Network error: ${e.message}"
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.weight(1f).height(56.dp),
                enabled = !isLoading && name.isNotBlank() && pin.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Login", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        TextButton(
            onClick = {
                scope.launch {
                    sessionManager.saveSession("Offline User", true)
                    onVerified()
                }
            }
        ) {
            Text("Skip & Use Offline Mode", color = TextGray)
        }
    }
}
