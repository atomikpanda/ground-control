package com.atomikpanda.groundcontrol.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.RuleFolder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Section(val route: String, val label: String, val icon: ImageVector) {
    SPECS("specs", "Specs", Icons.Filled.Inbox),
    MESSAGES("messages", "Messages", Icons.AutoMirrored.Filled.Chat),
    DECISIONS("decisions", "Decisions", Icons.Filled.RuleFolder),
    TASKS("tasks", "Tasks", Icons.AutoMirrored.Filled.Assignment),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}
