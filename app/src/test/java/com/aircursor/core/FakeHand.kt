package com.aircursor.core

/**
 * Builds a synthetic 21-point hand (fingers pointing up) for tests.
 * Coordinates are in hand units (wrist -> middle MCP = 1) and then scaled by [size]
 * around the wrist placed at (cx, cy).
 */
object FakeHand {
    enum class Shape { POINT, PINCH, TWO_FINGERS, OPEN_PALM, FIST }

    private val mcp = mapOf(
        HandIdx.INDEX_MCP to Pair(-0.3f, -0.95f),
        HandIdx.MIDDLE_MCP to Pair(0f, -1f),
        HandIdx.RING_MCP to Pair(0.25f, -0.95f),
        HandIdx.PINKY_MCP to Pair(0.45f, -0.85f),
    )

    fun build(shape: Shape, cx: Float = 0.5f, cy: Float = 0.7f, size: Float = 0.15f): List<Landmark> {
        val pts = Array(HandIdx.COUNT) { Pair(0f, 0f) }
        pts[HandIdx.WRIST] = Pair(0f, 0f)
        val extended = when (shape) {
            Shape.POINT, Shape.PINCH -> setOf(HandIdx.INDEX_MCP)
            Shape.TWO_FINGERS -> setOf(HandIdx.INDEX_MCP, HandIdx.MIDDLE_MCP)
            Shape.OPEN_PALM -> mcp.keys
            Shape.FIST -> emptySet()
        }
        for ((base, p) in mcp) {
            val (mx, my) = p
            pts[base] = p
            if (base in extended) {
                pts[base + 1] = Pair(mx, my - 0.4f)
                pts[base + 2] = Pair(mx, my - 0.65f)
                pts[base + 3] = Pair(mx, my - 0.85f)
            } else {
                // Bones keep their length when the finger curls (only the angle changes).
                pts[base + 1] = Pair(mx, my - 0.4f)
                pts[base + 2] = Pair(mx, my - 0.3f)
                pts[base + 3] = Pair(mx, my - 0.05f)
            }
        }
        pts[HandIdx.THUMB_CMC] = Pair(-0.25f, -0.2f)
        pts[HandIdx.THUMB_MCP] = Pair(-0.45f, -0.4f)
        pts[HandIdx.THUMB_IP] = Pair(-0.6f, -0.55f)
        pts[HandIdx.THUMB_TIP] = when (shape) {
            Shape.PINCH -> pts[HandIdx.INDEX_TIP].let { Pair(it.first + 0.05f, it.second + 0.05f) }
            Shape.FIST -> Pair(-0.15f, -0.75f)
            else -> Pair(-0.75f, -0.7f)
        }
        return pts.map { (x, y) -> Landmark(cx + x * size, cy + y * size) }
    }

    /** A different person: wider palm and longer finger bones than [build]. */
    fun otherPerson(shape: Shape, cx: Float = 0.5f, cy: Float = 0.7f, size: Float = 0.15f): List<Landmark> {
        val base = build(shape, 0f, 0f, 1f)
        return base.mapIndexed { i, p ->
            val wide = if (i >= HandIdx.INDEX_MCP) 1.25f else 1f
            val long = if (i >= HandIdx.INDEX_MCP && (i - HandIdx.INDEX_MCP) % 4 != 0) 1.15f else 1f
            Landmark(cx + p.x * wide * size, cy + p.y * long * size)
        }
    }

    fun scaled(lms: List<Landmark>, factor: Float): List<Landmark> =
        lms.map { Landmark(it.x * factor, it.y * factor, it.z) }
}
