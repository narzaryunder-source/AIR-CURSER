package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    private val w = 1081
    private val h = 2401

    @Test
    fun centerMapsToCenter() {
        val m = CoordinateMapper(w, h, margin = 0.15f)
        val p = m.map(0.5f, 0.5f)
        assertEquals(540f, p.x, 0.01f)
        assertEquals(1200f, p.y, 0.01f)
    }

    @Test
    fun activeBoxEdgesReachScreenEdges() {
        val m = CoordinateMapper(w, h, margin = 0.15f, mirrorX = false)
        val topLeft = m.map(0.15f, 0.15f)
        val bottomRight = m.map(0.85f, 0.85f)
        assertEquals(0f, topLeft.x, 0.5f)
        assertEquals(0f, topLeft.y, 0.5f)
        assertEquals(1080f, bottomRight.x, 0.5f)
        assertEquals(2400f, bottomRight.y, 0.5f)
    }

    @Test
    fun outsideBoxIsClamped() {
        val m = CoordinateMapper(w, h, margin = 0.15f, mirrorX = false)
        val p = m.map(0.02f, 0.99f)
        assertEquals(0f, p.x, 0.001f)
        assertEquals(2400f, p.y, 0.001f)
    }

    @Test
    fun mirrorFlipsX() {
        val m = CoordinateMapper(w, h, margin = 0.15f, mirrorX = true)
        // Hand on the camera's left edge of the box = user's right -> right side of screen.
        assertEquals(1080f, m.map(0.15f, 0.5f).x, 0.5f)
        assertEquals(0f, m.map(0.85f, 0.5f).x, 0.5f)
    }

    @Test
    fun zeroMarginIsIdentityScaling() {
        val m = CoordinateMapper(w, h, margin = 0f, mirrorX = false)
        assertEquals(270f, m.map(0.25f, 0.5f).x, 0.5f)
        assertEquals(1800f, m.map(0.5f, 0.75f).y, 0.5f)
    }

    @Test
    fun sensitivityScalesAroundCenter() {
        val normal = CoordinateMapper(w, h, margin = 0.15f, sensitivity = 1f, mirrorX = false)
        val fast = CoordinateMapper(w, h, margin = 0.15f, sensitivity = 2f, mirrorX = false)
        val dxNormal = normal.map(0.55f, 0.5f).x - 540f
        val dxFast = fast.map(0.55f, 0.5f).x - 540f
        assertEquals(dxNormal * 2f, dxFast, 0.5f)
    }

    @Test
    fun offsetShiftsTheBox() {
        val m = CoordinateMapper(w, h, margin = 0.15f, mirrorX = false, offsetX = 0.1f, offsetY = -0.1f)
        val p = m.map(0.6f, 0.4f)
        assertEquals(540f, p.x, 0.5f)
        assertEquals(1200f, p.y, 0.5f)
    }

    @Test
    fun offsetIsClampedToMargin() {
        assertEquals(0.15f, CoordinateMapper.clampOffset(0.4f, 0.15f), 0f)
        assertEquals(-0.15f, CoordinateMapper.clampOffset(-0.4f, 0.15f), 0f)
    }
}
