package com.atomikpanda.groundcontrol

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.atomikpanda.groundcontrol.data.CaptureDraft
import com.atomikpanda.groundcontrol.data.CaptureDraftPayload
import com.atomikpanda.groundcontrol.data.DataStoreCaptureDraftStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureDraftStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newDataStore(name: String, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("$name.preferences_pb").also { check(it.delete()) }
        }

    private fun draft(revision: Long, text: String = "idea") = CaptureDraft(
        subject = "",
        text = text,
        kind = "BRAINSTORM_SPEC",
        selectedConnectionId = "workspace",
        brainstormIdempotencyKey = "key-$revision",
        attemptedBrainstorm = CaptureDraftPayload("workspace", null, text),
        revision = revision,
    )

    @Test fun drafts_are_isolated_by_capture_context() = runTest {
        val store = DataStoreCaptureDraftStore(newDataStore("isolated", backgroundScope))
        val allWorkspaces = draft(1, "all workspaces")
        val workspace = draft(1, "workspace only")

        store.save(null, allWorkspaces)
        store.save("workspace", workspace)
        store.clear("workspace", 1)

        assertEquals(allWorkspaces, store.load(null))
        assertNull(store.load("workspace"))
    }

    @Test fun stale_clear_and_save_cannot_overwrite_newer_draft_input() = runTest {
        val store = DataStoreCaptureDraftStore(newDataStore("revision", backgroundScope))
        val old = draft(1, "old")
        val newer = draft(2, "newer")

        store.save("workspace", old)
        store.save("workspace", newer)
        store.clear("workspace", old.revision)
        store.save("workspace", old)

        assertEquals(newer, store.load("workspace"))
    }

    @Test fun discard_tombstone_rejects_a_late_save_but_allows_new_input() = runTest {
        val store = DataStoreCaptureDraftStore(newDataStore("discard-race", backgroundScope))
        val old = draft(1, "discard me")
        val replacement = draft(3, "new input")

        store.save("workspace", old)
        store.clear("workspace", 2)
        store.save("workspace", old)

        assertNull(store.load("workspace"))

        store.save("workspace", replacement)

        assertEquals(replacement, store.load("workspace"))
    }
}
