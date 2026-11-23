package com.dashlive.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun stableUpdateAfterPreview() {
        assertTrue(Updater.isNewer("v1.8.0", "1.8.0-rc.1"))
        assertFalse(Updater.isNewer("v1.7.0", "1.8.0-rc.1"))
        assertFalse(Updater.isNewer("v1.8.0-rc.1", "1.8.0-rc.1"))
        assertFalse(Updater.isNewer("v1.8.0", "1.8.0"))
    }
}
