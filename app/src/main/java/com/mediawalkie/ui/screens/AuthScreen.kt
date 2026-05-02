package com.mediawalkie.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mediawalkie.data.SessionManager
import com.mediawalkie.data.api.RegisterRequest
import com.mediawalkie.data.api.WalkieApi
import com.mediawalkie.ui.theme.PrimaryVibrant
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(sessionManager: SessionManager, api: WalkieApi, onVerified: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "MediaWalkie", style = MaterialTheme.typography.headlineLarge, color = PrimaryVibrant)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Call Sign (Name)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text("Secret PIN") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (errorMsg.isNotEmpty()) {
            Text(text = errorMsg, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val res = api.register(RegisterRequest(name, pin, "device123"))
                            if (res.user?.isVerified == true) {
                                sessionManager.saveSession(res.user.name, true)
                                onVerified()
                            } else {
                                errorMsg = res.error ?: "Account created! Pending admin verification."
                            }
                        } catch (e: Exception) {
                            errorMsg = "Network error: ${e.message}"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && name.isNotBlank() && pin.isNotBlank()
            ) {
                Text("Register")
            }

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val res = api.verify(RegisterRequest(name, pin, "device123"))
                            if (res.user?.isVerified == true) {
                                sessionManager.saveSession(res.user.name, true)
                                onVerified()
                            } else {
                                errorMsg = res.error ?: "Verification failed."
                            }
                        } catch (e: Exception) {
                            errorMsg = "Network error: ${e.message}"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && name.isNotBlank() && pin.isNotBlank()
            ) {
                Text("Login")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        TextButton(
            onClick = {
                scope.launch {
                    // Bypass the server entirely for offline mesh usage
                    sessionManager.saveSession("Offline User", true)
                    onVerified()
                }
            }
        ) {
            Text("Skip & Use Offline Mode", color = Color.Gray)
        }
    }
}
