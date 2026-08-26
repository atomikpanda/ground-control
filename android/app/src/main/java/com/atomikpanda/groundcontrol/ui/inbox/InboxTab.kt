package com.atomikpanda.groundcontrol.ui.inbox

import com.atomikpanda.groundcontrol.data.dto.InboxFilter
import com.atomikpanda.groundcontrol.data.dto.InboxState

enum class InboxTab(val filter: InboxFilter, val state: InboxState, val label: String) {
    ACTIVE(InboxFilter.ACTIVE, InboxState.ACTIVE, "Active"),
    ARCHIVED(InboxFilter.ARCHIVED, InboxState.ARCHIVED, "Archived"),
}
