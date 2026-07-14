package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.SpecAction
import com.atomikpanda.groundcontrol.data.availableActions
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion
import com.atomikpanda.groundcontrol.data.isReviewInteractive
import com.atomikpanda.groundcontrol.data.statusBanner
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecDetailScreen(vm: SpecDetailViewModel, title: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    // Show the loaded spec title once available; fall back to the spec id pre-load.
    val displayTitle = (state as? SpecDetailUiState.Content)?.detail?.title ?: title

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayTitle, maxLines = 1) },
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
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) LaunchedEffect(true) { vm.load()?.join(); pull.endRefresh() }

    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(Modifier.fillMaxSize()) {
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
                    Text(
                        "${sum.approved}/${sum.criteriaTotal} approved · ${sum.flagged} flagged · ${sum.unansweredQuestions} unanswered Q",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                QuestionRow(question, interactive, s.inFlight, vm)
            }
            if (interactive) item { AskQuestionRow(vm) }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
        s.banner?.let { BannerToast(it) { vm.dismissBanner() } }
    }

    s.blockers?.let { BlockersDialog(it, vm) }
    s.dispatchResult?.let { DispatchResultDialog(it, vm) }
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
    Row(Modifier.fillMaxWidth().padding(12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    tint = if (c.verdict == "approved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
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
        Text(c.text, Modifier.padding(start = 4.dp))
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
    vm: SpecDetailViewModel,
) {
    var draft by remember(q.id) { mutableStateOf(q.answer ?: "") }
    val busy = inFlight is ActionRef.Answer && inFlight.questionId == q.id
    Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
        Text(q.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (interactive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(if (q.answer == null) "answer" else "edit answer") },
                )
                if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
                else TextButton(onClick = { if (draft.isNotBlank()) vm.answer(q.id, draft) }) { Text("Send") }
            }
        } else {
            Text("answer: ${q.answer ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AskQuestionRow(vm: SpecDetailViewModel) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text("A new question blocks gated approve until answered.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Ask a question") },
            )
            TextButton(onClick = { if (text.isNotBlank()) { vm.ask(text); text = "" } }) { Text("Ask") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionBar(s: SpecDetailUiState.Content, vm: SpecDetailViewModel) {
    val actions = availableActions(s.detail.status)
    if (actions.isEmpty()) return
    var menu by remember { mutableStateOf(false) }
    var showReason by remember { mutableStateOf(false) }
    var showDispatch by remember { mutableStateOf(false) }
    val busy = s.inFlight != null

    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (SpecAction.REQUEST_CHANGES in actions)
                OutlinedButton(enabled = !busy, onClick = { showReason = true }) { Text("Request changes") }
            if (SpecAction.APPROVE in actions) {
                Box {
                    Button(enabled = !busy, onClick = { vm.approve(bypass = false) }) { Text("Approve") }
                    TextButton(enabled = !busy, onClick = { menu = true }) { Text("▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Approve anyway") },
                            onClick = { menu = false; vm.approve(bypass = true) },
                        )
                    }
                }
            }
            if (SpecAction.DISPATCH in actions)
                Button(enabled = !busy, onClick = { showDispatch = true }) { Text("Plan implementation") }
        }
    }

    if (showReason) ReasonDialog(onDismiss = { showReason = false }) { showReason = false; vm.requestChanges(it) }
    if (showDispatch) ConfirmDialog(
        title = "Plan the implementation?",
        body = "This spawns a task and starts writing the implementation plan on the host.",
        confirm = "Plan implementation",
        onDismiss = { showDispatch = false },
    ) { showDispatch = false; vm.dispatch() }
}

@Composable
private fun ReasonDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request changes") },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = reason.isNotBlank(), onClick = { onSubmit(reason) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
