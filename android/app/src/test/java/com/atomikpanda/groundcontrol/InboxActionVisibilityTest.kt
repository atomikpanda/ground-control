package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.InboxAction
import com.atomikpanda.groundcontrol.data.dto.InboxState
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.messages.threadInboxAction
import com.atomikpanda.groundcontrol.ui.specs.specInboxAction
import com.atomikpanda.groundcontrol.ui.specs.specInboxItemKey
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxActionVisibilityTest {
    @Test fun thread_archive_is_hidden_when_other_actions_take_precedence() {
        val ordinary = ThreadSummary(id = "ordinary", subject = "ordinary")
        assertEquals(InboxAction.ARCHIVE, threadInboxAction(ordinary))
        assertEquals(null, threadInboxAction(ordinary.copy(pinned = true)))
        assertEquals(null, threadInboxAction(ordinary.copy(needsYou = true)))
        assertEquals(null, threadInboxAction(ordinary.copy(needsDecision = true)))
    }

    @Test fun thread_restore_remains_available_for_archived_attention_items() {
        val archived = ThreadSummary(
            id = "archived",
            subject = "archived",
            inboxState = InboxState.ARCHIVED,
            pinned = true,
            needsYou = true,
            needsDecision = true,
        )
        assertEquals(InboxAction.RESTORE, threadInboxAction(archived))
    }

    @Test fun spec_archive_is_hidden_when_pinned_but_restore_remains_available() {
        val pinned = SpecSummary(id = "spec", title = "Spec", status = "draft", pinned = true)
        assertEquals(null, specInboxAction(pinned))
        assertEquals(
            InboxAction.RESTORE,
            specInboxAction(pinned.copy(inboxState = InboxState.ARCHIVED)),
        )
    }

    @Test fun spec_inbox_item_keys_include_the_workspace_connection() {
        assertEquals("workspace-a:spec", specInboxItemKey("workspace-a", "spec"))
        assertEquals("workspace-b:spec", specInboxItemKey("workspace-b", "spec"))
    }
}
