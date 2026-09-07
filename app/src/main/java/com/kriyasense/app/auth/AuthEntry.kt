package com.kriyasense.app.auth

import androidx.activity.compose.BackHandler
import com.kriyasense.app.ScreenBackStack
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kriyasense.app.R
import com.kriyasense.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrandImage(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.kriyasense_brand), contentDescription = "KriyaSense. Move better. Live stronger.", modifier = modifier)
}

@Composable
fun BrandEntry() {
    Surface(Modifier.fillMaxSize(), color = AppBackground) {
        Box(Modifier.safeDrawingPadding().padding(32.dp), contentAlignment = Alignment.Center) {
            BrandImage(Modifier.widthIn(max = 360.dp).fillMaxWidth())
        }
    }
}

@Composable
fun AuthEntry(auth: LocalAuth, onAuthenticated: () -> Unit) {
    val navigation = remember { ScreenBackStack("login") }
    val screen = navigation.screen
    BackHandler(enabled = navigation.canGoBack) { navigation.goBack() }
    // Changing forms discards passwords and validation messages.
    key(screen) {
        val signup = screen == "signup"
        var name by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        var errors by remember { mutableStateOf(AuthErrors()) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        Surface(Modifier.fillMaxSize(), color = AppBackground) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BrandImage(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (signup) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineLarge)
                    Text(if (signup) "Train smarter. Move better." else "Continue your movement journey.", color = SecondaryText)
                    Spacer(Modifier.height(4.dp))
                    if (signup) AuthField("Name", name, { name = it }, errors.name, enabled = !busy)
                    AuthField("Email", email, { email = it }, errors.email, keyboard = KeyboardType.Email, enabled = !busy)
                    AuthField("Password", password, { password = it }, errors.password, secret = true, enabled = !busy)
                    if (signup) AuthField("Confirm Password", confirmation, { confirmation = it }, errors.confirmation, secret = true, enabled = !busy)
                    errors.general?.let { Text(it, color = Warning, style = MaterialTheme.typography.bodyMedium) }
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                errors = withContext(Dispatchers.IO) {
                                    if (signup) auth.signUp(name, email, password, confirmation) else auth.login(email, password)
                                }
                                busy = false
                                if (!errors.any) {
                                    password = ""; confirmation = ""
                                    onAuthenticated()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(50)
                    ) { Text(if (busy) "Please wait…" else if (signup) "Create Account" else "Login") }
                    TextButton(enabled = !busy, onClick = { if (signup) navigation.goBack() else navigation.navigateTo("signup") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (signup) "Already have an account? Log In" else "Don't have an account? Sign Up", color = Lavender)
                    }
                    Text("Your prototype account stays on this device.", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AuthField(
    label: String, value: String, onChange: (String) -> Unit, error: String?,
    secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        label = { Text(label) }, singleLine = true, isError = error != null,
        supportingText = { if (error != null) Text(error, color = Warning) },
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        trailingIcon = if (secret) { { TextButton(onClick = { visible = !visible }, enabled = enabled) { Text(if (visible) "Hide" else "Show") } } } else null,
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CardSurface, unfocusedContainerColor = CardSurface,
            disabledContainerColor = CardSurface, errorContainerColor = CardSurface,
            focusedBorderColor = Lavender, unfocusedBorderColor = CardSurface,
            errorBorderColor = Warning, errorLabelColor = Warning, cursorColor = Lavender
        )
    )
}
