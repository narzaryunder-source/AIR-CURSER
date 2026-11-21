package com.aircursor.air

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which Air windows show, and which get a fresh page view. A window that comes back on screen
 * (from minimized, or from under a full-size window) must get a fresh view: a reused view whose
 * surface went away could stay black for good.
 */
class StackPlanTest {
    private fun win(minimized: Boolean = false, full: Boolean = false, wasOnScreen: Boolean = true, surfaceLost: Boolean = false) =
        StackIn(minimized, full, wasOnScreen, surfaceLost)

    @Test
    fun backFromMinimizedGetsAFreshView() {
        // VS Code was minimized (not on screen); now it is shown again in front, maximized.
        val plan = stackPlan(listOf(win(full = true, wasOnScreen = false)))
        assertEquals(listOf(StackOut(Show.ON, freshView = true)), plan)
    }

    @Test
    fun minimizingNeedsNoNewView() {
        val plan = stackPlan(listOf(win(minimized = true, wasOnScreen = true)))
        assertEquals(listOf(StackOut(Show.OFF, freshView = false)), plan)
    }

    @Test
    fun aWindowThatStaysOnScreenKeepsItsView() {
        // Focus changes, moves, insets: nothing came back, nothing is rebuilt.
        val plan = stackPlan(listOf(win(), win()))
        assertEquals(listOf(StackOut(Show.ON, false), StackOut(Show.ON, false)), plan)
    }

    @Test
    fun windowsUnderAFullSizeOneRestAndComeBackFresh() {
        // Front: maximized VS Code. Behind it: the Air browser (was on screen before VS Code covered it).
        val covered = stackPlan(listOf(win(full = true), win(wasOnScreen = true)))
        assertEquals(StackOut(Show.ON, false), covered[0])
        assertEquals("the browser under it rests", StackOut(Show.UNDER, false), covered[1])

        // VS Code is minimized: the browser is uncovered and shows again on a fresh view.
        val uncovered = stackPlan(listOf(win(minimized = true), win(wasOnScreen = false)))
        assertEquals(StackOut(Show.OFF, false), uncovered[0])
        assertEquals(StackOut(Show.ON, true), uncovered[1])
    }

    @Test
    fun aMinimizedWindowDoesNotCoverTheOnesBehind() {
        val plan = stackPlan(listOf(win(minimized = true, full = true), win(), win()))
        assertEquals(listOf(Show.OFF, Show.ON, Show.ON), plan.map { it.show })
    }

    @Test
    fun windowedWindowsDoNotHideTheOnesBehind() {
        val plan = stackPlan(listOf(win(full = false), win(full = true), win()))
        assertEquals(listOf(Show.ON, Show.ON, Show.UNDER), plan.map { it.show })
    }

    @Test
    fun aHiddenWindowThatStaysHiddenIsNotRebuilt() {
        val plan = stackPlan(listOf(win(full = true), win(wasOnScreen = false), win(minimized = true, wasOnScreen = false)))
        assertEquals(listOf(false, false, false), plan.map { it.freshView })
    }

    @Test
    fun backFromTheHomeScreenEveryWindowOnScreenGetsANewView() {
        // The app was in the background: windows that were on screen kept their place but lost
        // their drawing surface – these used to stay black.
        val plan = stackPlan(listOf(win(surfaceLost = true), win(full = false, surfaceLost = true)))
        assertEquals(listOf(true, true), plan.map { it.freshView })
    }

    @Test
    fun backFromTheHomeScreenHiddenWindowsWaitUntilShown() {
        val plan = stackPlan(listOf(win(full = true, surfaceLost = true), win(surfaceLost = true), win(minimized = true, surfaceLost = true)))
        assertEquals(listOf(true, false, false), plan.map { it.freshView })
        assertEquals(listOf(Show.ON, Show.UNDER, Show.OFF), plan.map { it.show })
    }
}
