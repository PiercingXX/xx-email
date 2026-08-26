package dev.xxemail.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    account: String,
    initialTo: String,
    initialCc: String,
    initialSubject: String,
    threadId: String,
    quoteMessageId: String,
    mode: String,
    onDone: () -> Unit,
) {
    val vm = rememberComposeViewModel(account, threadId, quoteMessageId, mode)
    val context = LocalContext.current
    var showCcBcc by remember { mutableStateOf(initialCc.isNotBlank()) }
    var scheduleMenu by remember { mutableStateOf(false) }

    // Prefill is a side effect: run it after composition settles, not inside remember {}
    // (which re-runs on config changes and wipes in-progress edits — the VM dedupes too).
    LaunchedEffect(initialTo, initialCc, initialSubject, threadId, quoteMessageId, mode) {
        vm.prefill(initialTo, initialCc, initialSubject, threadId, quoteMessageId, mode)
    }

    // Closure 2: the resolver's MIME type rides along with each picked document.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            vm.addAttachment(
                context,
                uri,
                uri.lastPathSegment ?: "attachment",
                context.contentResolver.getType(uri),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Discard") }
                },
                actions = {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.AttachFile, "Attach file")
                    }
                    IconButton(onClick = { scheduleMenu = true }) {
                        Icon(Icons.Filled.Schedule, "Schedule send")
                    }
                    DropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }) {
                        ComposeViewModel.schedulePresets(java.time.ZonedDateTime.now()).forEach { (label, at) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    scheduleMenu = false
                                    vm.send(at.toInstant().toEpochMilli(), onDone)
                                },
                            )
                        }
                    }
                    if (vm.sending) {
                        CircularProgressIndicator(Modifier.padding(12.dp))
                    } else {
                        IconButton(onClick = { vm.send(null, onDone) }, enabled = vm.to.isNotBlank() || vm.cc.isNotBlank() || vm.bcc.isNotBlank()) {
                            Icon(Icons.Filled.Send, "Send")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = vm.to,
                onValueChange = { vm.to = it },
                label = { Text("To") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (showCcBcc) {
                OutlinedTextField(value = vm.cc, onValueChange = { vm.cc = it }, label = { Text("Cc") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = vm.bcc, onValueChange = { vm.bcc = it }, label = { Text("Bcc") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            } else {
                // F8: the affordance is actually clickable (it previously looked tappable but was not).
                Text(
                    "Add Cc/Bcc",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { showCcBcc = true },
                )
            }
            OutlinedTextField(
                value = vm.subject,
                onValueChange = { vm.subject = it },
                label = { Text("Subject") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (vm.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.attachments.forEach { staged ->
                        InputChip(
                            selected = false,
                            onClick = { vm.removeAttachment(staged.file) },
                            label = { Text(staged.file.name.substringAfter('-')) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove") },
                        )
                    }
                }
            }
            if (vm.offeredForwardAttachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { vm.includeOriginalAttachments() },
                        label = { Text("Include original attachments (${vm.offeredForwardAttachments.size})") },
                        leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
                    )
                    IconButton(onClick = { vm.declineForwardAttachments() }) {
                        Icon(Icons.Filled.Close, "Don't include attachments")
                    }
                }
            }
            OutlinedTextField(
                value = vm.body,
                onValueChange = { vm.body = it },
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                minLines = 10,
                placeholder = { Text("Write your message…") },
            )
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            AssistChip(
                onClick = { showCcBcc = !showCcBcc },
                label = { Text(if (showCcBcc) "Hide Cc/Bcc" else "Show Cc/Bcc") },
            )
        }
    }
}
