package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import com.atomikpanda.groundcontrol.ui.components.WorkspaceBadge
import com.atomikpanda.groundcontrol.ui.theme.WorkspaceIdentity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.atomikpanda.groundcontrol.ui.activity.LiveChip
import com.atomikpanda.groundcontrol.ui.activity.PhaseStepper
import com.atomikpanda.groundcontrol.ui.activity.phaseStepFor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.SpecAction
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.data.availableActions
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion
import com.atomikpanda.groundcontrol.data.isReviewInteractive
import com.atomikpanda.groundcontrol.data.statusBanner
import com.atomikpanda.groundcontrol.ui.components.MultilineComposeInput
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecDetailScreen(vm: SpecDetailViewModel, title: String, identity: WorkspaceIdentity? = null, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    // Poll the in-flight task only while the spec is dispatched; stop at terminal.
    val pollStatus = (state as? SpecDetailUiState.Content)?.detail?.status
    DisposableEffect(pollStatus) {
        val job = if (pollStatus == "dispatched") vm.startActivityPolling() else null
        onDispose { job?.cancel() }
    }

    // Show the loaded spec title once available; fall back to the spec id pre-load.
    val displayTitle = (state as? SpecDetailUiState.Content)?.detail?.title ?: title

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        identity?.let { WorkspaceBadge(it, size = 20.dp); Spacer(Modifier.width(8.dp)) }
                        Text(displayTitle, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        bottomBar = { (state as? SpecDetailUiState.Content)?.let { ActionBar(it, vm) } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                SpecDetailUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is SpecDetailUiState.Error -> ErrorView(s, vm, onBack)
                is SpecDetailUiState.Content -> ContentView(s, vm)
            }
        }
    }
}

@Composable
private fun ErrorView(s: SpecDetailUiState.Error, vm: SpecDetailViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        val msg = when (s.kind) {
            ErrorKind.AUTH -> "Token rejected. Fix this connection in Settings."
            ErrorKind.NOT_FOUND -> "This spec is no longer available."
            ErrorKind.NETWORK -> s.message
        }
        Text(msg, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.kind == ErrorKind.NETWORK) Button(onClick = { vm.load() }) { Text("Retry") }
            if (s.kind == ErrorKind.NOT_FOUND) Button(onClick = onBack) { Text("Back to inbox") }
            if (s.kind == ErrorKind.AUTH) OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentView(s: SpecDetailUiState.Content, vm: SpecDetailViewModel) {
    val d = s.detail
    val interactive = isReviewInteractive(d.status)
    val answerDrafts by vm.answerDrafts.collectAsStateWithLifecycle()
    val askDraft by vm.askDraft.collectAsStateWithLifecycle()
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) LaunchedEffect(true) { vm.load()?.join(); pull.endRefresh() }

    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(Modifier.fillMaxSize()) {
            d.unansweredLead?.let { lead ->
                item { LeadBanner(lead, Modifier.padding(16.dp, 8.dp)) }
            }
            item {
                Column(Modifier.padding(16.dp, 8.dp)) {
                    val sum = d.summary
                    statusBanner(d.status, d.taskSlug)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } ?: Text("● ${d.status}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "repos: ${d.affectedRepos.joinToString().ifBlank { "—" }}",
                        style = MonoStyle,
                    )
                    ReadinessChipsRow(sum, Modifier.padding(top = 6.dp))
                    if (d.taskSlug != null) {
                        Spacer(Modifier.height(8.dp))
                        PhaseStepper(phaseStepFor(d.taskPhase, d.taskFinished), compact = true)
                        LiveChip(
                            lastActivityIso = d.taskLastActivityAt,
                            merged = d.taskFinished,
                            nowMillis = System.currentTimeMillis(),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            item { SpecBodyMarkdown(d.bodyMarkdown, Modifier.padding(16.dp, 4.dp)) }
            if (d.nonGoals.isNotEmpty()) {
                item { SectionLabel("NON-GOALS") }
                items(d.nonGoals) { BulletText(it) }
            }
            if (d.risks.isNotEmpty()) {
                item { SectionLabel("RISKS") }
                items(d.risks) { BulletText(it) }
            }
            if (d.criteria.isNotEmpty()) {
                item { SectionLabel("ACCEPTANCE CRITERIA") }
                items(d.criteria, key = { it.id }) { criterion ->
                    CriterionRow(criterion, interactive, s.inFlight, vm)
                }
            }
            item { SectionLabel("OPEN QUESTIONS") }
            items(d.questions, key = { it.id }) { question ->
                QuestionRow(
                    q = question,
                    interactive = interactive,
                    inFlight = s.inFlight,
                    draft = answerDrafts[question.id] ?: (question.answer ?: ""),
                    onDraftChange = { vm.setAnswerDraft(question.id, it) },
                    onSend = { vm.answer(question.id, it) },
                )
            }
            if (interactive) item {
                AskQuestionRow(
                    draft = askDraft,
                    onDraftChange = { vm.setAskDraft(it) },
                    onSend = { vm.ask(it) },
                )
            }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
        s.banner?.let { BannerToast(it) { vm.dismissBanner() } }
    }

    s.blockers?.let { BlockersDialog(it, vm) }
    s.dispatchResult?.let { DispatchResultDialog(it, vm) }
}

/** ac1: a single, prominent lead atop a review-phase spec that has unanswered open questions,
 *  pointing the operator at the inline answer fields as the path to approval. Rendered only when
 *  [SpecDetail.unansweredLead] is non-null, so a spec with no open questions is unchanged (ac5). */
@Composable
private fun LeadBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
    )

@Composable
private fun BulletText(text: String) =
    Text("• $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 2.dp))

@Composable
private fun CriterionRow(
    c: ReviewCriterion,
    interactive: Boolean,
    inFlight: ActionRef?,
    vm: SpecDetailViewModel,
) {
    val busy = inFlight is ActionRef.Verdict && inFlight.criterionId == c.id
    Row(Modifier.fillMaxWidth().padding(12.dp, 4.dp), verticalAlignment = Alignment.Top) {
        if (busy) {
            CircularProgressIndicator(Modifier.padding(8.dp))
        } else if (interactive) {
            IconToggleButton(
                checked = c.verdict == "approved",
                onCheckedChange = { vm.setVerdict(c.id, if (c.verdict == "approved") "unreviewed" else "approved") },
            ) {
                Icon(
                    Icons.Filled.Check,
                    "approve",
                    tint = if (c.verdict == "approved") LocalSemanticColors.current.approval else MaterialTheme.colorScheme.outline,
                )
            }
            IconToggleButton(
                checked = c.verdict == "flagged",
                onCheckedChange = { vm.setVerdict(c.id, if (c.verdict == "flagged") "unreviewed" else "flagged") },
            ) {
                Icon(
                    Icons.Filled.Flag,
                    "flag",
                    tint = if (c.verdict == "flagged") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            Text(verdictGlyph(c.verdict), Modifier.padding(8.dp))
        }
        // Criterion text, then its evidence (AC-evidence loop): each backing ref on a muted line, or a
        // single muted "unverified" when nothing backs it. Long refs cap at 2 lines so they can't run away.
        Column(Modifier.padding(start = 4.dp, top = 8.dp)) {
            Text(c.text)
            if (isUnverified(c.evidence)) {
                Text(
                    "unverified",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                evidenceLabels(c.evidence).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun verdictGlyph(v: String) = when (v) {
    "approved" -> "✓"
    "flagged" -> "⚑"
    else -> "•"
}

@Composable
private fun QuestionRow(
    q: ReviewQuestion,
    interactive: Boolean,
    inFlight: ActionRef?,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    val busy = inFlight is ActionRef.Answer && inFlight.questionId == q.id
    Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
        Text(q.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (interactive) {
            // ac7: reusable auto-expanding multi-line compose box (#282) — a long answer wraps + grows.
            // ac8: its Send is a prominent FilledIconButton, disabled while blank (no silent no-op tap).
            // ac3: this inline answer is the PRIMARY action; Request-changes is a secondary sheet.
            // The VM owns the draft (ac9) and clears it on a successful send — we don't clear here, so a
            // failed send keeps the typed text.
            MultilineComposeInput(
                value = draft,
                onValueChange = onDraftChange,
                onSend = { if (draft.isNotBlank()) onSend(draft) },
                placeholder = if (q.answer == null) "answer" else "edit answer",
                enabled = !busy,
                inFlight = busy,
                sendDescription = "Send answer",
            )
        } else {
            Text("answer: ${q.answer ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AskQuestionRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text("A new question blocks gated approve until answered.", style = MaterialTheme.typography.bodySmall)
        // ac7 multi-line + ac8 disabled-when-blank via the shared compose box. The VM owns the ask draft
        // (ac9) and clears it on a successful ask.
        MultilineComposeInput(
            value = draft,
            onValueChange = onDraftChange,
            onSend = { if (draft.isNotBlank()) onSend(draft) },
            placeholder = "Ask a question",
            sendIcon = Icons.AutoMirrored.Filled.HelpOutline,
            sendDescription = "Ask a question",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionBar(s: SpecDetailUiState.Content, vm: SpecDetailViewModel) {
    val actions = availableActions(s.detail.status)
    if (actions.isEmpty()) return
    val requestChangesDraft by vm.requestChangesDraft.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var showReason by remember { mutableStateOf(false) }
    var showDispatch by remember { mutableStateOf(false) }
    var showApproveConfirm by remember { mutableStateOf(false) }
    val busy = s.inFlight != null

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            // ac2: unanswered questions are the ONLY blocker → guide toward answering rather than a
            // generic disabled/blocked Approve. Uses the tested SpecDetail.approveGuidance.
            s.detail.approveGuidance?.let { guidance ->
                Text(
                    guidance,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (SpecAction.REQUEST_CHANGES in actions)
                    OutlinedButton(enabled = !busy, onClick = { showReason = true }) { Text("Request changes") }
                if (SpecAction.APPROVE in actions) {
                    // Approve and its overflow are siblings in the ActionBar Row (spaced), not stacked in a
                    // Box — a Box would place both at TopStart and overlap them. The menu anchors to the
                    // overflow via its own Box.
                    Button(enabled = !busy, onClick = { showApproveConfirm = true }) { Text("Approve") }
                    Box {
                        IconButton(enabled = !busy, onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More approve actions")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Approve anyway") },
                                onClick = { menu = false; vm.approve(bypass = true) },
                            )
                        }
                    }
                }
                if (SpecAction.DISPATCH in actions)
                    FilledTonalButton(enabled = !busy, onClick = { showDispatch = true }) { Text("Plan implementation") }
            }
        }
    }

    if (showReason) RequestChangesSheet(
        draft = requestChangesDraft,
        onDraftChange = { vm.setRequestChangesDraft(it) },
        onDismiss = { showReason = false },
        onSend = { vm.requestChanges(it) },
    )
    if (showApproveConfirm) ConfirmDialog(
        title = "Approve this spec?",
        body = "Marks the spec approved and unblocks implementation.",
        confirm = "Approve",
        onDismiss = { showApproveConfirm = false },
    ) { showApproveConfirm = false; vm.approve(bypass = false) }
    if (showDispatch) ConfirmDialog(
        title = "Plan the implementation?",
        body = "This spawns a task and starts writing the implementation plan on the host.",
        confirm = "Plan implementation",
        onDismiss = { showDispatch = false },
    ) { showDispatch = false; vm.dispatch() }
}

/** ac6: Request-changes opens the app's standard bottom sheet (the ModalBottomSheet idiom shared with
 *  QueueScreen.RejectSheet / DecisionCard.CommentSheet), reusing the #282 multi-line compose box —
 *  not a cramped AlertDialog. Clears the field before dispatch so the Send button disables (no
 *  double-submit), then hides + dismisses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestChangesSheet(
    draft: String,
    onDraftChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Request changes", style = MaterialTheme.typography.titleSmall)
            // The reason lives in the VM draft (not local state) so a failed submit KEEPS it — the VM
            // clears it only on success. We dismiss after firing; on failure the operator reopens the
            // sheet with the reason intact instead of losing it (Greptile P2).
            MultilineComposeInput(
                value = draft,
                onValueChange = onDraftChange,
                onSend = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) onDismiss()
                        }
                    }
                },
                placeholder = "Reason for changes…",
                sendDescription = "Send request-changes",
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BlockersDialog(blockers: List<String>, vm: SpecDetailViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dismissBlockers() },
        title = { Text("Can't approve yet") },
        text = { Column { blockers.forEach { Text("• $it") } } },
        confirmButton = {
            TextButton(onClick = { vm.dismissBlockers(); vm.approve(bypass = true) }) { Text("Approve anyway") }
        },
        dismissButton = { TextButton(onClick = { vm.dismissBlockers() }) { Text("OK") } },
    )
}

@Composable
private fun DispatchResultDialog(info: DispatchInfo, vm: SpecDetailViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dismissDispatchResult() },
        title = { Text(if (info.spawned) "Dispatched (spawned task)" else "Dispatched") },
        text = {
            Column {
                Text("task: ${info.taskSlug}", style = MonoStyle)
                if (info.handoff.isNotBlank()) Text(info.handoff.take(280), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissDispatchResult() }) { Text("Done") } },
    )
}

@Composable
private fun BannerToast(message: String, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(8.dp), tonalElevation = 4.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
