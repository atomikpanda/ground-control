package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    vm: ConversationViewModel,
    title: String,
    onBack: () -> Unit,
    onViewSpec: (specId: String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    val displayTitle = (state as? ConversationUiState.Content)?.thread?.subject?.takeIf { it.isNotBlank() } ?: title

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.requestSpec() }) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "Make this a spec")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                ConversationUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is ConversationUiState.Error -> ConversationErrorView(s, vm, onBack)
                is ConversationUiState.Content -> ConversationContentView(s, vm, onViewSpec)
            }
        }
    }
}

@Composable
private fun ConversationErrorView(
    s: ConversationUiState.Error,
    vm: ConversationViewModel,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        val msg = when (s.kind) {
            ErrorKind.AUTH -> "Token rejected. Fix this connection in Settings."
            ErrorKind.NOT_FOUND -> "This conversation is no longer available."
            ErrorKind.NETWORK -> s.message
        }
        Text(msg, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.kind == ErrorKind.NETWORK) Button(onClick = { vm.load() }) { Text("Retry") }
            if (s.kind == ErrorKind.NOT_FOUND) Button(onClick = onBack) { Text("Back") }
            if (s.kind == ErrorKind.AUTH) OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationContentView(
    s: ConversationUiState.Content,
    vm: ConversationViewModel,
    onViewSpec: (specId: String) -> Unit = {},
) {
    val thread = s.thread
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) LaunchedEffect(true) { vm.load()?.join(); pull.endRefresh() }
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom-most item when the list changes. The optional
    // "Awaiting reply…" indicator is the last item, so the target is the total
    // item count minus one (covers both messages growing and the indicator
    // appearing/disappearing).
    val itemCount = thread.messages.size + if (thread.awaitingReply) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // "View spec ->" affordance — only shown when this thread has been linked to a spec.
        thread.specId?.let { specId ->
            OutlinedButton(
                onClick = { onViewSpec(specId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("View spec →")
            }
        }
        Box(Modifier.weight(1f).nestedScroll(pull.nestedScrollConnection)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                items(thread.messages, key = { it.id }) { message ->
                    MessageRow(message)
                }
                // Bottom-anchored: chat flows downward, so the hint belongs after
                // the last message, where the eye lands after the auto-scroll.
                if (thread.awaitingReply) {
                    item {
                        Text(
                            "Awaiting reply…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp, 4.dp),
                        )
                    }
                }
            }
            PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
        }
        ComposeBar(s, vm)
    }
}

@Composable
private fun MessageRow(message: Message) {
    val isHuman = message.role == "human"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (isHuman) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isHuman) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isHuman) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ComposeBar(state: ConversationUiState.Content, vm: ConversationViewModel) {
    var draft by remember { mutableStateOf("") }
    // Text of the in-flight send, buffered so it can be restored if the send fails.
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.inFlight, state.sendError) {
        if (!state.inFlight) {
            // Send settled: restore the user's text on failure, then drop the buffer.
            if (state.sendError != null) pending?.let { draft = it }
            pending = null
        }
    }
    Surface(tonalElevation = 3.dp) {
        Column {
            state.sendError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    singleLine = true,
                    enabled = !state.inFlight,
                )
                if (state.inFlight) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                } else {
                    IconButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                // Clear optimistically; the LaunchedEffect above restores
                                // the text if the send fails.
                                pending = draft
                                vm.send(draft)
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
