package com.atomikpanda.groundcontrol.ui.messages

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.components.MultilineComposeInput
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    vm: ConversationViewModel,
    title: String,
    onBack: () -> Unit,
    onViewSpec: (specId: String) -> Unit = {},
    onOpenEntity: (kind: String, id: String) -> Unit = { _, _ -> },
    onOpenWorkItem: (String) -> Unit = {},
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
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                ConversationUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is ConversationUiState.Error -> ConversationErrorView(s, vm, onBack)
                is ConversationUiState.Content -> ConversationContentView(s, vm, onViewSpec, onOpenEntity, onOpenWorkItem)
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
    onOpenEntity: (kind: String, id: String) -> Unit = { _, _ -> },
    onOpenWorkItem: (String) -> Unit = {},
) {
    val thread = s.thread
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) LaunchedEffect(true) { vm.load()?.join(); pull.endRefresh() }
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom-most item when the list changes, but only when
    // the user was already near the bottom beforehand -- otherwise a new
    // message (or the "Awaiting reply…" indicator appearing) would yank the
    // view away from wherever they'd scrolled up to, e.g. while reading back
    // through history with the keyboard open. "Near the bottom" is judged
    // against the *previous* item count's last index: LazyColumn doesn't move
    // the scroll position on its own when items are appended, so at the start
    // of this effect `listState.layoutInfo` still reflects where the user was
    // looking before this recomposition. The optional "Awaiting reply…"
    // indicator is the last item, so the scroll target is the total item
    // count minus one (covers both messages growing and the indicator
    // appearing/disappearing).
    val itemCount = thread.messages.size + if (thread.awaitingReply) 1 else 0
    // rememberSaveable (not plain remember) so this survives rotation/config
    // change: rememberLazyListState() is itself saveable, so without this the
    // gate below would compare against a reset-to-0 previousItemCount post-
    // rotation (`lastVisibleIndex >= -2`, always true) and yank the list to
    // the bottom even if the user had scrolled up.
    val previousItemCount = rememberSaveable { mutableIntStateOf(0) }
    // Tracked separately from previousItemCount so the "awaiting reply…"
    // indicator flipping on/off (which also moves itemCount by one) can't
    // masquerade as a new message below.
    val previousMessageCount = rememberSaveable { mutableIntStateOf(0) }
    var showJumpToLatest by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(itemCount) {
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        val wasNearBottom = lastVisibleIndex == null || lastVisibleIndex >= previousItemCount.intValue - 2
        val newInboundMessage = thread.messages.size > previousMessageCount.intValue &&
            thread.messages.lastOrNull()?.role != "human"
        if (itemCount > 0 && wasNearBottom) {
            listState.animateScrollToItem(itemCount - 1)
        } else if (newInboundMessage) {
            // The gate above deliberately left the scroll position alone
            // because the user had scrolled away from the bottom -- surface
            // a pill instead of yanking the view.
            showJumpToLatest = true
        }
        previousItemCount.intValue = itemCount
        previousMessageCount.intValue = thread.messages.size
    }

    // If the user scrolls back near the bottom on their own (without tapping
    // the pill), drop it rather than leaving it stuck on screen. Reads
    // listState.layoutInfo directly (not the outer itemCount val) so the
    // long-lived coroutine below always sees the live item count rather than
    // a value captured from whichever composition first launched it.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index to info.totalItemsCount
        }.collect { (lastVisibleIndex, totalItems) ->
            if (showJumpToLatest && lastVisibleIndex != null && lastVisibleIndex >= totalItems - 2) {
                showJumpToLatest = false
            }
        }
    }

    // The "active" decision is the most recent decision message that has no
    // human reply after it — i.e. it's still awaiting an answer. Scanning
    // back to the last human message and checking the tail after it (rather
    // than just `lastOrNull()`) means a trailing agent note posted after an
    // unanswered decision doesn't accidentally clear the free-text gate.
    val lastHumanIndex = thread.messages.indexOfLast { it.role == "human" }
    val activeDecisionIndex = thread.messages.indices
        .drop(lastHumanIndex + 1)
        .lastOrNull { thread.messages[it].kind == "decision" }
    val activeDecision = if (activeDecisionIndex == null) null else thread.messages[activeDecisionIndex].decision
    val allowFreeText = activeDecision?.allowFreeText ?: true

    // When free text is gated, the active decision card may have scrolled off
    // screen (e.g. after a bottom-anchored auto-scroll). Bring it back into
    // view so the "choose an option above" hint has something to point at.
    // Keyed on the decision index + the gate itself so this only re-fires
    // when the active decision actually changes, not on every recompose.
    LaunchedEffect(activeDecisionIndex, allowFreeText) {
        if (!allowFreeText && activeDecisionIndex != null) {
            listState.animateScrollToItem(activeDecisionIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // MOS-224 activity strip: pinned at the very top of the conversation (below the
        // app bar, above everything else — including the "View spec" affordance) since it
        // reads as ambient status/context for the whole thread. Only for threads linked to
        // a task; a thread with no task_slug shows nothing here.
        if (thread.taskSlug != null) { ActivityStrip(s.journal) }
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
        // "Related work item" card -- only shown when this thread belongs to a WorkItem
        // (T1/T2 mothership + T4 GC DTO). Taps through the shared item resolver route
        // rather than duplicating its phase-dispatch logic here.
        thread.workItem?.let { wi ->
            ElevatedCard(
                onClick = { onOpenWorkItem(wi.id) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                ListItem(
                    overlineContent = { Text("Related work item") },
                    headlineContent = { Text(wi.title.ifBlank { wi.id }) },
                    supportingContent = { if (wi.phase.isNotBlank()) Text(wi.phase.replace('_', ' ')) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open work item") },
                )
            }
        }
        Box(Modifier.weight(1f).nestedScroll(pull.nestedScrollConnection)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                itemsIndexed(thread.messages, key = { _, message -> message.id }) { index, message ->
                    // A decision is "answered" once a human message exists after it —
                    // resolved decisions must not offer live buttons (prevents silent
                    // double-answering when scrolling back through history). A human
                    // message exists after `index` iff `lastHumanIndex` (the last human
                    // message in the whole thread) is past it — avoids allocating a
                    // fresh sublist per item per recompose.
                    val answered = index < lastHumanIndex
                    MessageRow(
                        message,
                        inFlight = s.inFlight,
                        answered = answered,
                        onOption = { vm.send(it) },
                        onOpenEntity = onOpenEntity,
                    )
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
            if (showJumpToLatest) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clickable {
                            showJumpToLatest = false
                            scope.launch { listState.animateScrollToItem(itemCount - 1) }
                        },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 4.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            "Jump to latest",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
        ComposeBar(s, vm, allowFreeText = allowFreeText)
    }
}

/**
 * MOS-224 in-thread activity strip: a slim, subordinate-to-messages row showing the last
 * couple of task-journal entries so "Awaiting reply" isn't a black box. Deliberately quiet —
 * small type, muted color, single-line-each — it's ambient status, not part of the
 * conversation. Renders a subtle "no recent activity" line (not an error) when the linked
 * task's journal came back empty or unavailable, per the graceful-degradation requirement.
 */
@Composable
private fun ActivityStrip(journal: List<JournalEntry>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (journal.isEmpty()) {
                Text(
                    "No recent activity yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val now = System.currentTimeMillis()
                journal.forEach { entry ->
                    Text(
                        journalStripLine(entry, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Max characters shown for a journal entry's label before it's ellipsized -- entries can be
 *  terse or verbose (a whole paragraph); the strip is one line each and must never blow up
 *  the layout. */
private const val ACTIVITY_STRIP_LABEL_MAX_CHARS = 60

/** Compact single-line label for one activity-strip entry, e.g. "wrote parser · 3m ago".
 *  Prefers the entry's short `action` tag; falls back to its `message` when there's no
 *  action (the common case for ad-hoc `mship journal` notes). Pure + JVM-testable (mirrors
 *  DecisionCard's formatCommentMessage / NotificationFormat's parse helpers). */
internal fun journalStripLine(entry: JournalEntry, nowMillis: Long): String {
    val label = entry.action?.takeIf { it.isNotBlank() } ?: entry.message
    val truncated = if (label.length > ACTIVITY_STRIP_LABEL_MAX_CHARS) {
        label.take(ACTIVITY_STRIP_LABEL_MAX_CHARS - 1).trimEnd() + "…"
    } else {
        label
    }
    val ago = relativeTimeAgo(entry.timestamp, nowMillis)
    return if (ago != null) "$truncated · $ago" else truncated
}

/** "just now" / "3m ago" / "2h ago" / "5d ago" relative-time label. Returns null when
 *  [iso] doesn't parse (accepts both `...Z` and offset forms) so callers can omit the
 *  suffix instead of showing garbage -- a malformed timestamp must never crash the strip. */
internal fun relativeTimeAgo(iso: String, nowMillis: Long): String? {
    val thenMillis = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: return null
    val deltaSeconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    return when {
        deltaSeconds < 60 -> "just now"
        deltaSeconds < 3600 -> "${deltaSeconds / 60}m ago"
        deltaSeconds < 86400 -> "${deltaSeconds / 3600}h ago"
        else -> "${deltaSeconds / 86400}d ago"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    inFlight: Boolean = false,
    answered: Boolean = false,
    onOption: (String) -> Unit = {},
    onOpenEntity: (kind: String, id: String) -> Unit = { _, _ -> },
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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (isHuman) Arrangement.End else Arrangement.Start,
    ) {
        if (isHuman) {
            // Plain-text bubble: native long-press-to-select + copy via
            // SelectionContainer works cleanly here — there's no competing
            // tap-gesture handling for SelectionContainer's own long-press
            // recognizer to conflict with (unlike the markdown bubble below).
            SelectionContainer {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(4.dp),
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            // The markdown renderer (multiplatform-markdown-renderer) detects
            // link taps with its own raw pointerInput + AnnotatedString
            // string-annotation gesture handling rather than the newer
            // LinkAnnotation API — the same low-level pattern that's long been
            // known to have its taps swallowed by SelectionContainer's own
            // long-press-to-select gesture recognizer once both are attached
            // to the same subtree. Wrapping this bubble in SelectionContainer
            // would risk breaking link taps, so it falls back to an explicit
            // long-press-to-copy affordance on the bubble itself instead of
            // native text selection.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(4.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            clipboard.setText(AnnotatedString(message.text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                    ),
            ) {
                // Full-contrast (not the muted onSurfaceVariant role) + markdown —
                // agent replies are prose/code the operator reads closely, unlike
                // the human's own short outbound messages, which stay plain Text.
                MessageMarkdown(
                    text = message.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    onOpenEntity = onOpenEntity,
                )
            }
        }
    }
}

@Composable
private fun ComposeBar(state: ConversationUiState.Content, vm: ConversationViewModel, allowFreeText: Boolean = true) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    Surface(
        tonalElevation = 3.dp,
        // navigationBarsPadding() first, then imePadding(): the standard
        // stacking order so the bar sits above the nav bar when the keyboard
        // is closed and above the keyboard when it's open, without double-
        // padding for both at once. The flat 12dp on top is the actual
        // "don't sit flush" gap in either state.
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 12.dp),
    ) {
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
            // The shared multiline auto-expanding compose input (#282) — grows as you type,
            // Return = newline, the button sends.
            MultilineComposeInput(
                value = draft,
                onValueChange = vm::onDraftChange,
                onSend = { if (draft.isNotBlank()) vm.send(draft) },
                enabled = !state.inFlight,
                inFlight = state.inFlight,
            )
        }
    }
}
