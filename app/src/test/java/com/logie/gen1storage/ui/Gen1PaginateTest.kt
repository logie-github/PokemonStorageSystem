package com.logie.gen1storage.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splitting behind Bill's text box.
 *
 * What these are really guarding is that nothing he says goes missing. The
 * pages used to be cut against a TextMeasurer that could be holding a
 * different font to the one on screen, which fitted about twice as much on a
 * line as the box really has — so pages came out around six lines long, the
 * line cap hid the three that did not fit, and whole clauses were never shown
 * at all.
 */
class Gen1PaginateTest {

    /** The longest thing he says, which is where it first went wrong. */
    private val storageSystem =
        "BILL: THIS IS THE POKéMON STORAGE SYSTEM. IT WORKS ON THE SAME NETWORK " +
            "AS THE PCS INSIDE POKéMON CENTERS ALL OVER THE KANTO REGION. NORMALLY, " +
            "YOUR POKéMON ARE SENT BACK TO THE LAB OR RESEARCH FACILITY YOUR POKéDEX " +
            "IS REGISTERED WITH. THIS APP LETS YOU REACH THAT SYSTEM AND STORE YOUR " +
            "POKéMON ON YOUR PHONE, INSTEAD."

    @Test
    fun `no page is taller than the box holds`() {
        for (columns in 12..40) {
            paginate(listOf(storageSystem), columns).forEach { page ->
                assertTrue(
                    "page over $GEN1_DIALOGUE_LINES lines at $columns columns: $page",
                    wrappedLineCount(page, columns) <= GEN1_DIALOGUE_LINES,
                )
            }
        }
    }

    @Test
    fun `every word survives the split`() {
        for (columns in 12..40) {
            val pages = paginate(listOf(storageSystem), columns)
            assertEquals(
                "words lost at $columns columns",
                storageSystem.split(" ").filter { it.isNotEmpty() },
                pages.flatMap { it.split(" ") }.filter { it.isNotEmpty() },
            )
        }
    }

    @Test
    fun `each thing said opens its own page`() {
        val pages = paginate(listOf("SHORT ONE.", "SHORT TWO."), 40)
        assertEquals(listOf("SHORT ONE.", "SHORT TWO."), pages)
    }

    @Test
    fun `a word longer than the line is kept rather than dropped`() {
        val long = "A".repeat(90)
        val pages = paginate(listOf(long), 20)
        assertEquals(long, pages.joinToString("").replace(" ", ""))
    }

    @Test
    fun `wrapping counts the space between words`() {
        // Ten columns: "ABCDE FGHI" is exactly ten characters and fits; one
        // more word does not.
        assertEquals(1, wrappedLineCount("ABCDE FGHI", 10))
        assertEquals(2, wrappedLineCount("ABCDE FGHIJ", 10))
    }
}
