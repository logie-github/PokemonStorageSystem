package com.logie.gen1storage

import com.logie.gen1storage.update.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version comparison.
 *
 * The obvious implementations are both wrong in the same place: comparing the
 * strings, or parsing them as decimals, each make 2.10.0 older than 2.9.0. That
 * is a bug nobody sees until the tenth patch release, so it is pinned here.
 */
class UpdateCheckerTest {

    @Test
    fun `a later version is newer`() {
        assertTrue(UpdateChecker.isNewer("2.1.0", "2.0.0"))
        assertTrue(UpdateChecker.isNewer("3.0.0", "2.9.9"))
        assertTrue(UpdateChecker.isNewer("2.0.1", "2.0.0"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(UpdateChecker.isNewer("2.1.0", "2.1.0"))
    }

    @Test
    fun `an earlier version is not newer`() {
        assertFalse(UpdateChecker.isNewer("2.0.0", "2.1.0"))
        assertFalse(UpdateChecker.isNewer("1.9.9", "2.0.0"))
    }

    @Test
    fun `ten is greater than nine, which string and decimal order both get wrong`() {
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.9.0"))
        assertFalse(UpdateChecker.isNewer("2.9.0", "2.10.0"))
        assertTrue(UpdateChecker.isNewer("10.0.0", "9.0.0"))
    }

    @Test
    fun `missing parts count as zero`() {
        assertFalse(UpdateChecker.isNewer("2.0", "2.0.0"))
        assertTrue(UpdateChecker.isNewer("2.0.1", "2.0"))
        assertFalse(UpdateChecker.isNewer("2", "2.0.0"))
    }

    @Test
    fun `a debug suffix on the running build does not read as newer`() {
        assertTrue(UpdateChecker.isNewer("2.1.0", "2.0.0-debug"))
        assertFalse(UpdateChecker.isNewer("2.0.0", "2.0.0-debug"))
    }
}
