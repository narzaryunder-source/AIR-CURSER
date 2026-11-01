package com.aircursor.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aircursor.R
import com.aircursor.auth.AuthManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sign in once with Google or a phone number (OTP). You stay signed in on this phone. */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("choose") } // choose | phone | code
    var name by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("+91") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableIntStateOf(0) }

    LaunchedEffect(resendIn) {
        if (resendIn > 0) {
            delay(1000)
            resendIn--
        }
    }

    fun fullNumber() = country.trim().let { if (it.startsWith("+")) it else "+$it" } + phone.filter { it.isDigit() }

    fun sendCode(resend: Boolean) {
        val a = activity ?: return
        error = null
        busy = true
        AuthManager.sendCode(a, fullNumber(), name, resend) { ev ->
            a.runOnUiThread {
                busy = false
                when (ev) {
                    AuthManager.PhoneEvent.CodeSent -> {
                        step = "code"
                        resendIn = 30
                    }
                    AuthManager.PhoneEvent.SignedIn -> Unit // the app moves on by itself
                    is AuthManager.PhoneEvent.Error -> error = ev.message
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brand.handGradient)
                    .padding(top = 56.dp, bottom = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(6.dp),
                    ) { BrandLogo(size = 84.dp) }
                    Spacer(Modifier.height(18.dp))
                    Text("AirCursor", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Control your phone in the air.\nRun a full PC on your phone.",
                        color = Color.White.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (step == "code") "Enter the code" else "Welcome",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                if (!AuthManager.configured) {
                    // Sign-in service not set up yet (owner adds it later): continue with a name.
                    Text(
                        "Account sign-in will be available soon. Enter your name to continue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton(
                        "Continue",
                        onClick = { AuthManager.continueAsGuest(context, name) },
                        enabled = name.isNotBlank(),
                        icon = R.drawable.ic_ui_chevron,
                    )
                } else {
                    when (step) {
                        "choose" -> {
                            Text(
                                "Sign in once – you stay signed in on this phone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (AuthManager.googleAvailable(context)) {
                                PrimaryButton(
                                    "Continue with Google",
                                    onClick = {
                                        val a = activity ?: return@PrimaryButton
                                        error = null
                                        busy = true
                                        scope.launch {
                                            error = AuthManager.signInWithGoogle(a)
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    icon = R.drawable.ic_ui_user,
                                )
                            }
                            SecondaryButton(
                                "Continue with phone number",
                                onClick = {
                                    error = null
                                    step = "phone"
                                },
                                enabled = !busy,
                                icon = R.drawable.ic_ui_phone,
                            )
                        }
                        "phone" -> {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(40) },
                                label = { Text("Your name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = country,
                                    onValueChange = { country = it.filter { c -> c.isDigit() || c == '+' }.take(5) },
                                    label = { Text("Code") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.width(92.dp),
                                )
                                OutlinedTextField(
                                    value = phone,
                                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(12) },
                                    label = { Text("Mobile number") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            PrimaryButton(
                                "Send code",
                                onClick = { sendCode(resend = false) },
                                enabled = !busy && name.isNotBlank() && phone.length >= 8,
                                icon = R.drawable.ic_ui_mail,
                            )
                            TextButton(onClick = { step = "choose" }) { Text("Back") }
                        }
                        "code" -> {
                            Text(
                                "We sent a 6-digit code to ${fullNumber()}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                                label = { Text("6-digit code") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PrimaryButton(
                                "Verify",
                                onClick = {
                                    val a = activity ?: return@PrimaryButton
                                    error = null
                                    busy = true
                                    AuthManager.verifyCode(a, code) { err ->
                                        a.runOnUiThread {
                                            busy = false
                                            error = err
                                        }
                                    }
                                },
                                enabled = !busy && code.length == 6,
                                icon = R.drawable.ic_ui_check,
                            )
                            Row {
                                TextButton(onClick = { sendCode(resend = true) }, enabled = !busy && resendIn == 0) {
                                    Text(if (resendIn > 0) "Resend code in ${resendIn}s" else "Resend code")
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    code = ""
                                    step = "phone"
                                }) { Text("Change number") }
                            }
                        }
                    }
                }

                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Please wait…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Your camera images never leave your phone. Signing out never deletes your data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
