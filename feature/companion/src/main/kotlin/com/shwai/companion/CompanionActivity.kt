package com.shwai.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.shwai.core.security.Providers

class CompanionActivity : ComponentActivity() {
    private val graph get() = (application as ShwAiApp).graph
    private val gmailLinked = mutableStateOf(false)
    private val mediaGranted = mutableStateOf(false)
    private val status = mutableStateOf<String?>(null)

    private val authResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        try {
            storeGmailToken(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data).accessToken)
        } catch (e: Exception) {
            status.value = "Google authorization failed"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gmailLinked.value = graph.tokens.validAccessToken(Providers.GMAIL) != null
        refreshMedia()
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        refreshMedia()
    }

    private fun mediaPermissionList(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun refreshMedia() {
        mediaGranted.value = mediaPermissionList().any {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun authorizeGmail() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(Providers.GMAIL_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { res ->
                val pending = res.pendingIntent
                if (res.hasResolution() && pending != null) {
                    authResult.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    storeGmailToken(res.accessToken)
                }
            }
            .addOnFailureListener { status.value = "Google authorization failed" }
    }

    /** GIS returns no expiry or refresh token; assume the standard 1h lifetime minus a margin. */
    private fun storeGmailToken(token: String?) {
        if (token == null) { status.value = "No token returned"; return }
        graph.tokens.saveAccessToken(Providers.GMAIL, token, 55 * 60_000L)
        gmailLinked.value = true
        status.value = null
    }

    @Composable
    private fun Screen() {
        val mediaPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshMedia() }
        var apiKey by remember { mutableStateOf("") }
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("ShwAI", style = MaterialTheme.typography.headlineMedium)
                    Step("1. Enable keyboard", "Turn on ShwAI in system keyboard settings.", "Open settings") {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                    Step("2. Select keyboard", "Switch the active input method to ShwAI.", "Switch") {
                        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    }
                    Step(
                        "3. Media access",
                        if (mediaGranted.value) "Granted. ShwAI can find images, video and audio by name."
                        else "Lets ShwAI find images, video and audio by name. Choose \"Allow all\" for best results.",
                        "Grant",
                    ) { mediaPermissions.launch(mediaPermissionList()) }
                    Step(
                        "4. Gmail drafts",
                        if (gmailLinked.value) "Linked. Re-authorize when the session expires (about 1 hour)." else "Grants draft-only access (gmail.compose).",
                        if (gmailLinked.value) "Re-authorize" else "Sign in with Google",
                        ::authorizeGmail,
                    )
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("5. Gemini API key", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = apiKey, onValueChange = { apiKey = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text("API key") },
                            )
                            Button(
                                enabled = apiKey.isNotBlank(),
                                onClick = { graph.tokenStore.put(Providers.GEMINI_KEY, apiKey.trim()); apiKey = ""; status.value = "Key saved" },
                            ) { Text("Save") }
                        }
                    }
                    status.value?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }

    @Composable
    private fun Step(title: String, body: String, action: String, onClick: () -> Unit) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onClick) { Text(action) }
            }
        }
    }
}
