package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.messages.EntityLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityLinkTest {
    @Test fun parses_item_link() {
        assertEquals("item" to "wi-x", EntityLink.parse("groundcontrol://item?id=wi-x"))
    }

    @Test fun parses_spec_link() {
        assertEquals("spec" to "s1", EntityLink.parse("groundcontrol://spec?id=s1"))
    }

    @Test fun parses_task_link() {
        assertEquals("task" to "k1", EntityLink.parse("groundcontrol://task?id=k1"))
    }

    @Test fun ignores_http_links() {
        assertNull(EntityLink.parse("https://example.com"))
    }

    @Test fun ignores_thread_links_handled_elsewhere() {
        assertNull(EntityLink.parse("groundcontrol://thread?id=t1"))
    }

    @Test fun ignores_missing_id() {
        assertNull(EntityLink.parse("groundcontrol://item"))
        assertNull(EntityLink.parse("groundcontrol://item?id="))
    }

    @Test fun ignores_malformed_uri() {
        assertNull(EntityLink.parse("not a uri"))
    }
}
