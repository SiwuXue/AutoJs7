package org.autojs.autojs.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSwitchStateTest {

    @Test fun toggleUsesPersistedIntentNotTransientServiceState() {
        assertTrue(McpSwitchState.nextEnabled(false))
        assertFalse(McpSwitchState.nextEnabled(true))
    }

    @Test fun startupAndPageRecreationKeepEnabledIntentVisible() {
        assertEquals(
            McpSwitchState.Summary.STARTING,
            McpSwitchState.summary(true, false, false, null),
        )
        assertEquals(
            McpSwitchState.Summary.RUNNING,
            McpSwitchState.summary(true, true, true, null),
        )
    }

    @Test fun socketOrForegroundServiceAloneIsNotRunning() {
        assertEquals(
            McpSwitchState.Summary.STARTING,
            McpSwitchState.summary(true, true, false, null),
        )
        assertEquals(
            McpSwitchState.Summary.STARTING,
            McpSwitchState.summary(true, false, true, null),
        )
    }

    @Test fun failureAndExplicitStopHaveDistinctSummaries() {
        assertEquals(
            McpSwitchState.Summary.FAILED,
            McpSwitchState.summary(true, false, false, "port unavailable"),
        )
        assertEquals(
            McpSwitchState.Summary.STOPPED,
            McpSwitchState.summary(false, true, true, "old failure"),
        )
    }
}
