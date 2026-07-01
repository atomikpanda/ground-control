package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    vm: ConversationViewModel,
    title: String,
    onBack: () -> Unit,
    onViewSpec: (specId: String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load()?.join(); vm.startPolling() }

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

    // The "active" decision is the most recent decision message that has no
    // human reply after it — i.e. it's still awaiting an answer. Scanning
    // back to the last human message and checking the tail after it (rather
    // than just `lastOrNull()`) means a trailing agent note posted after an
    // unanswered decision doesn't accidentally clear the free-text gate.
    val lastHumanIndex = thread.messages.indexOfLast { it.role == "human" }
    val activeDecision = thread.messages
        .drop(lastHumanIndex + 1)
        .lastOrNull { it.kind == "decision" }
        ?.decision
    val allowFreeText = activeDecision?.allowFreeText ?: true

    // Keep the latest message visible when the keyboard opens — the existing
    // effect only scrolls on message-count changes, not on IME show.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && itemCount > 0) listState.animateScrollToItem(itemCount - 1)
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
                itemsIndexed(thread.messages, key = { _, message -> message.id }) { index, message ->
                    // A decision is "answered" once a human message exists after it —
                    // resolved decisions must not offer live buttons (prevents silent
                    // double-answering when scrolling back through history).
                    val answered = thread.messages.drop(index + 1).any { it.role == "human" }
                    MessageRow(message, inFlight = s.inFlight, answered = answered, onOption = { vm.send(it) })
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
        ComposeBar(s, vm, allowFreeText = allowFreeText)
    }
}

@Composable
private fun MessageRow(
    message: Message,
    inFlight: Boolean = false,
    answered: Boolean = false,
    onOption: (String) -> Unit = {},
) {
    if (message.kind == "decision" && message.decision != null) {
        DecisionCard(
            text = message.text,
            decision = message.decision,
            enabled = !inFlight && !answered,
            resolved = answered,
            onOption = onOption,
        )
        return
    }
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

/** An agent decision rendered as the question text plus one tappable button per
 *  option — the recommended option (if any) is accented with the theme's
 *  "question" semantic color. Tapping an option reuses the same send path as
 *  the free-text compose bar ([onOption] is wired to `vm.send` by the caller).
 *
 *  [resolved] marks a decision that a human has already answered (a human
 *  message exists later in the thread) — its options are disabled and it's
 *  visually muted so it reads as history rather than a live prompt. */
@Composable
private fun DecisionCard(
    text: String,
    decision: Decision,
    enabled: Boolean,
    resolved: Boolean = false,
    onOption: (String) -> Unit,
) {
    val colors = LocalSemanticColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(4.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (resolved) {
                    Text(
                        "Answered",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                decision.options.forEachIndexed { index, option ->
                    val isRecommended = index == decision.recommended
                    if (isRecommended) {
                        Button(
                            onClick = { onOption(option) },
                            enabled = enabled,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.question),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(option) }
                    } else {
                        FilledTonalButton(
                            onClick = { onOption(option) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(option) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeBar(state: ConversationUiState.Content, vm: ConversationViewModel, allowFreeText: Boolean = true) {
    val draft by vm.draft.collectAsStateWithLifecycle()
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
            if (!allowFreeText) {
                // The pending decision opted out of free text — the compose bar's
                // job here is just to point back at the decision card's options.
                Text(
                    "Choose an option above to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = vm::onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    singleLine = true,
                    enabled = !state.inFlight,
                )
                if (state.inFlight) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                } else {
                    IconButton(
                        onClick = { if (draft.isNotBlank()) vm.send(draft) },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
