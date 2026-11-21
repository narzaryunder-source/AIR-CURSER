package com.aircursor.air

import org.junit.Assert.assertEquals
import org.junit.Test

class BlankCheckTest {
    private val bg = AirBrowser.PAGE_BG

    private fun all(c: Int, n: Int = 24 * 24) = IntArray(n) { c }

    @Test
    fun onlyTheWindowBackgroundMeansNothingWasDrawn() {
        assertEquals(BlankCheck.Result.CLEAR, BlankCheck.judge(all(bg)))
    }

    @Test
    fun blackAndTransparentAreBlack() {
        assertEquals(BlankCheck.Result.BLACK, BlankCheck.judge(all(0xFF000000.toInt())))
        assertEquals(BlankCheck.Result.BLACK, BlankCheck.judge(all(0)))   // a TextureView never drawn
    }

    @Test
    fun aRealPageIsFine() {
        val page = all(0xFFFFFFFF.toInt()).also { it[100] = 0xFF202124.toInt() }   // white page, some text
        assertEquals(BlankCheck.Result.FINE, BlankCheck.judge(page))
        val dark = all(bg).also { it[5] = 0xFF8AB4F8.toInt() }   // a dark page with one link
        assertEquals(BlankCheck.Result.FINE, BlankCheck.judge(dark))
        assertEquals("a white page still loading is drawn", BlankCheck.Result.FINE, BlankCheck.judge(all(0xFFFFFFFF.toInt())))
    }

    @Test
    fun noPixelsMeansNoPicture() {
        assertEquals(BlankCheck.Result.NOTHING, BlankCheck.judge(IntArray(0)))
    }

    @Test
    fun aNewViewFirstThenTheWholePageInANewSession() {
        for (r in listOf(BlankCheck.Result.CLEAR, BlankCheck.Result.BLACK, BlankCheck.Result.NOTHING)) {
            // Pure black (the reported window) is handled too: it was left alone before.
            assertEquals("$r 1", BlankCheck.Step.NEW_VIEW, BlankCheck.next(1, r))
            assertEquals("$r 2", BlankCheck.Step.NEW_SESSION, BlankCheck.next(2, r))
            assertEquals("$r 3", BlankCheck.Step.NEW_SESSION, BlankCheck.next(3, r))
            assertEquals("$r 4", BlankCheck.Step.GIVE_UP, BlankCheck.next(4, r))
        }
        assertEquals(BlankCheck.Step.GIVE_UP, BlankCheck.next(1, BlankCheck.Result.FINE))
    }

    @Test
    fun theReportedScreenshotIsBlank() {
        // The black Air Store window from the phone: every pixel 0,0,0.
        assertEquals(BlankCheck.Result.BLACK, BlankCheck.judge(IntArray(24 * 24) { 0xFF000000.toInt() }))
    }
}
