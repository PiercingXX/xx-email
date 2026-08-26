package dev.xxemail.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xxemail.appGraph
import dev.xxemail.sync.SyncScheduler
import dev.xxemail.ui.nav.LocalAuthLauncher
import kotlinx.coroutines.launch

/**
 * First-run setup: bring-your-own OAuth client ID (privacy: no developer middleman server),
 * then the standard Google consent screen via any browser.
 */
@Composable
fun SetupScreen(onAccountReady: (String) -> Unit) {
    val graph = LocalContext.current.appGraph
    val authLauncher = LocalAuthLauncher.current
    val savedClientId by graph.settings.clientIdFlow.collectAsStateWithLifecycle(initialValue = null)

    var clientId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(savedClientId) {
        if (clientId == null && savedClientId != null) clientId = savedClientId
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Welcome to XX Email", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.padding(8.dp))
        Text(
            "A private, open-source Gmail client. No tracking, no ads, no Play Services required.\n\n" +
                "One-time setup:\n" +
                "1. Create a Google Cloud project at console.cloud.google.com\n" +
                "2. Enable the Gmail API\n" +
                "3. Create an OAuth client ID of type \"Android\" (package dev.xxemail)\n" +
                "   — or type \"Desktop\" if you prefer the loopback-free flow\n" +
                "4. Paste the client ID below (full instructions: docs/oauth-setup.md)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(12.dp))
        OutlinedTextField(
            value = clientId.orEmpty(),
            onValueChange = { clientId = it },
            label = { Text("OAuth client ID") },
            placeholder = { Text("1234567890-abc.apps.googleusercontent.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.padding(12.dp))
        Button(
            enabled = !busy && !clientId.isNullOrBlank() && clientId!!.endsWith("apps.googleusercontent.com"),
            onClick = {
                busy = true
                error = null
                val id = clientId!!.trim()
                scope.launch { runCatching { graph.settings.setClientId(id) } }
                authLauncher { result ->
                    result.fold(
                        onSuccess = { email ->
                            scope.launch {
                                graph.accounts.register(email)
                                SyncScheduler.kickImmediate(graph)
                                busy = false
                                onAccountReady(email)
                            }
                        },
                        onFailure = { t ->
                            error = t.message ?: "Authorization failed"
                            busy = false
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Waiting for Google…" else "Sign in with Google") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let {
            Spacer(Modifier.padding(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
