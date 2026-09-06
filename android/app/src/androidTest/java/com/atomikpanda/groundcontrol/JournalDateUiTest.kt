package com.atomikpanda.groundcontrol

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.ui.components.JournalEntryRow
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class JournalDateUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>(effectContext = Dispatchers.Main)

    @Test fun local_midnight_changes_today_to_yesterday_even_on_a_short_dst_day() {
        val now = mutableStateOf(ZonedDateTime.parse("2026-03-08T23:30:00-04:00[America/New_York]"))
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.US) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                GroundControlTheme {
                    JournalEntryRow(JournalEntry("2026-03-08T00:30:00-05:00", "Earlier today"), now.value)
                }
            }
        }
        composeRule.onNode(dateLabel("Today")).assertIsDisplayed()
        composeRule.runOnIdle { now.value = now.value.plusHours(1) }
        // Only 23 hours old, but now on yesterday's local calendar date.
        composeRule.onNode(dateLabel("Yesterday")).assertIsDisplayed()
    }

    @Test fun yesterday_remains_yesterday_across_the_year_boundary() {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.US) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                GroundControlTheme {
                    JournalEntryRow(
                        JournalEntry("2026-12-31T23:30:00-05:00", "Previous year, but yesterday"),
                        ZonedDateTime.parse("2027-01-01T00:30:00-05:00[America/New_York]"),
                    )
                }
            }
        }
        composeRule.onNode(dateLabel("Yesterday")).assertIsDisplayed()
    }

    private fun dateLabel(day: String) = SemanticsMatcher("journal date is $day") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("$day\n") } == true
    }
}
