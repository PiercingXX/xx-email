package dev.xxemail.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.xxemail.appGraph
import dev.xxemail.data.db.ThreadEntity
import dev.xxemail.ui.components.EmptyState
import dev.xxemail.ui.components.ThreadRow
import dev.xxemail.data.repo.SwipeAction
import kotlinx.coroutines.launch

private val OPERATOR_HINTS = listOf(
    "from:alice@example.com",
    "has:attachment",
    "after:2026/01/01 before:2026/02/01",
    "subject:invoice is:unread",
    "newer_than:7d",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    account: String,
    onBack: () -> Unit,
    onOpenThread: (String) -> Unit,
) {
    val graph = LocalContext.current.appGraph
    val repo = remember(account) { graph.mailRepository(account) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ThreadEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        loading = true
        searched = true
        scope.launch {
            // Operator queries go server-side; plain terms hit the local index first.
            results = if (q.contains(':')) {
                runCatching { repo.searchServer(q) }.getOrDefault(emptyList())
            } else {
                val local = runCatching { repo.searchLocal(q) }.getOrDefault(emptyList())
                if (local.isNotEmpty()) local else runCatching { repo.searchServer(q) }.getOrDefault(emptyList())
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search mail (from:, has:attachment …)") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = ::runSearch) { Icon(Icons.Filled.Search, "Search") }
                },
            )
            if (!searched) {
                Column(Modifier.padding(16.dp)) {
                    Text("Try:", style = MaterialTheme.typography.labelLarge)
                    OPERATOR_HINTS.forEach { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { thread ->
                    ThreadRow(
                        thread = thread,
                        onClick = { onOpenThread(thread.id) },
                        onStarToggle = {},
                        onLongClick = {},
                        selected = false,
                        leftAction = SwipeAction.ARCHIVE,
                        rightAction = SwipeAction.ARCHIVE,
                        onSwipe = {},
                    )
                    HorizontalDivider()
                }
            }
            if (searched && !loading && results.isEmpty()) EmptyState("No results")
        }
    }
}
