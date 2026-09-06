package com.atomikpanda.groundcontrol.ui.components

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.notify.parseTimestampMillis
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.delay

internal enum class JournalTone { ROUTINE, SUCCESS, WARNING, ERROR, QUESTION }

/** Structured failures take priority; prose is never interpreted as a status. */
internal fun journalTone(entry: JournalEntry): JournalTone {
    val testState = entry.testState?.trim()?.lowercase()
    val category = entry.category?.trim()?.lowercase()
    if (testState == "fail" || category == "error" || category == "failure") return JournalTone.ERROR
    if (testState == "mixed" || category == "warning" || category == "blocked") return JournalTone.WARNING
    if (!entry.openQuestion.isNullOrBlank()) return JournalTone.QUESTION
    if (testState == "pass" || category == "success") return JournalTone.SUCCESS
    return JournalTone.ROUTINE
}

@Composable
private fun journalAccent(tone: JournalTone): Color {
    val colors = LocalSemanticColors.current
    return when (tone) {
        JournalTone.ROUTINE -> MaterialTheme.colorScheme.primary
        JournalTone.SUCCESS -> colors.approval
        JournalTone.WARNING -> colors.blocker
        JournalTone.ERROR -> colors.error
        JournalTone.QUESTION -> colors.question
    }
}

/** Shared by the full task timeline and the Console's recent journal entries. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JournalEntryRow(entry: JournalEntry, now: ZonedDateTime, modifier: Modifier = Modifier) {
    val accent = journalAccent(journalTone(entry))
    val testState = entry.testState?.takeIf { it.isNotBlank() }
    val category = entry.category?.takeIf { it.isNotBlank() }
    val action = entry.action?.takeIf { it.isNotBlank() }
    val question = entry.openQuestion?.takeIf { it.isNotBlank() }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            Modifier.drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            JournalTimestamp(entry.timestamp, now)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (testState != null) {
                    when (testState.trim().lowercase()) {
                        "pass" -> JournalBadge("Tests passed", journalAccent(JournalTone.SUCCESS))
                        "fail" -> JournalBadge("Tests failed", journalAccent(JournalTone.ERROR))
                        "mixed" -> JournalBadge("Tests mixed", journalAccent(JournalTone.WARNING))
                        else -> JournalBadge("Tests: $testState", journalAccent(JournalTone.ROUTINE))
                    }
                }
                category?.let { JournalBadge(it, accent) }
                action?.let { JournalBadge(it, MaterialTheme.colorScheme.primary) }
                entry.repo?.takeIf { it.isNotBlank() }?.let {
                    JournalBadge(it, MaterialTheme.colorScheme.secondary)
                }
                entry.iteration?.let { JournalBadge("Iteration $it", MaterialTheme.colorScheme.secondary) }
                if (testState == null && category == null && action == null) {
                    JournalBadge(if (question == null) "Journal" else "Question", accent)
                }
            }
            Text(entry.message, style = MaterialTheme.typography.bodySmall)
            question?.let {
                Surface(
                    color = LocalSemanticColors.current.question.copy(alpha = 0.10f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text("Open question", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** One lifecycle-aware clock per journal screen, not one timer per row. */
@Composable
internal fun rememberJournalNow(): State<ZonedDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initial = remember { ZonedDateTime.now() }
    return produceState(initial, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = ZonedDateTime.now()
                delay(60_000)
            }
        }
    }
}

@Composable
private fun JournalTimestamp(timestamp: String, now: ZonedDateTime) {
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val zone = remember(now.zone) { TimeZone.getTimeZone(now.zone) }
    val event = remember(timestamp, now.zone) {
        parseTimestampMillis(timestamp)?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
    }
    val today = now.toLocalDate()
    val showYear = event?.year != today.year
    val dateFormat = remember(locale, zone, showYear) {
        val skeleton = if (showYear) "yMMMd" else "MMMd"
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply { timeZone = zone }
    }
    val timeFormat = remember(locale, zone, use24Hour) {
        val skeleton = if (use24Hour) "Hm" else "hm"
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply { timeZone = zone }
    }
    val relativeFormat = remember(locale) {
        RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale), null, RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
        )
    }
    val label = remember(timestamp, event, today, dateFormat, timeFormat, relativeFormat) {
        event?.let {
            val date = Date(it.toInstant().toEpochMilli())
            // Compare local calendar days, not 24-hour durations (DST and midnight matter).
            val day = when (it.toLocalDate()) {
                today -> relativeFormat.format(RelativeDateTimeFormatter.Direction.THIS, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
                today.minusDays(1) -> relativeFormat.format(RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
                else -> dateFormat.format(date)
            }
            "$day\n${timeFormat.format(date)}"
        } ?: timestamp
    }
    // One semantic label keeps the contextual date and time together for screen readers.
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun JournalBadge(label: String, accent: Color) {
    Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        // Accent text (notably light-theme green) can miss AA contrast: keep the label neutral.
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
