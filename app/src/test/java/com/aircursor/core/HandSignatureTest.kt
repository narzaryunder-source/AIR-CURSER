package com.aircursor.core

import com.aircursor.core.FakeHand.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HandSignatureTest {
    private val rnd = Random(3)

    /** World landmarks in metres (hand ~9 cm), with ~1 mm noise. */
    private fun world(shape: Shape, other: Boolean = false, size: Float = 0.09f): List<Landmark> {
        val lms = if (other) FakeHand.otherPerson(shape, 0f, 0f, size) else FakeHand.build(shape, 0f, 0f, size)
        return lms.map {
            Landmark(
                it.x + rnd.nextGaussian().toFloat() * 0.001f,
                it.y + rnd.nextGaussian().toFloat() * 0.001f,
                rnd.nextGaussian().toFloat() * 0.001f,
            )
        }
    }

    private fun enrolled(): HandProfile {
        val e = HandEnroller(45)
        while (!e.done) e.add(HandSignature.features(world(Shape.OPEN_PALM))!!, "Right")
        return e.build()!!
    }

    @Test
    fun sameHandMatchesInAnyPose() {
        val p = enrolled()
        for (shape in Shape.values()) {
            val d = p.distance(HandSignature.features(world(shape))!!)
            assertTrue("$shape distance $d", d < 2.5f)
        }
    }

    @Test
    fun differentHandIsFarAway() {
        val p = enrolled()
        val d = p.distance(HandSignature.features(world(Shape.OPEN_PALM, other = true))!!)
        assertTrue("distance $d", d > 3f)
    }

    @Test
    fun profileSurvivesSaveAndLoad() {
        val p = enrolled()
        val q = HandProfile.parse(p.serialize())
        assertNotNull(q)
        assertEquals(p.handedness, q!!.handedness)
        val f = HandSignature.features(world(Shape.POINT))!!
        assertEquals(p.distance(f), q.distance(f), 1e-4f)
        assertNull(HandProfile.parse("garbage"))
    }

    @Test
    fun gateAcceptsOwnerAndRejectsStranger() {
        val gate = OwnerGate(enrolled())
        var t = 0L
        repeat(10) { gate.update(HandSignature.features(world(Shape.POINT)), "Right", t); t += 33 }
        assertEquals(OwnerGate.State.OWNER, gate.state)

        // Owner leaves, a stranger comes in.
        repeat(20) { gate.update(null, null, t); t += 33 }
        assertEquals(OwnerGate.State.NO_HAND, gate.state)
        var allowed = false
        repeat(30) {
            allowed = allowed || gate.update(HandSignature.features(world(Shape.POINT, other = true)), "Right", t)
            t += 33
        }
        assertFalse(allowed)
        assertEquals(OwnerGate.State.STRANGER, gate.state)
    }

    @Test
    fun gateRejectsOtherSideHand() {
        val gate = OwnerGate(enrolled())
        var t = 0L
        repeat(10) { gate.update(HandSignature.features(world(Shape.POINT)), "Left", t); t += 33 }
        assertEquals(OwnerGate.State.STRANGER, gate.state)
    }

    @Test
    fun handSwapWithoutLosingTrackingIsCaught() {
        val gate = OwnerGate(enrolled())
        var t = 0L
        repeat(10) { gate.update(HandSignature.features(world(Shape.POINT)), "Right", t); t += 33 }
        repeat(30) { gate.update(HandSignature.features(world(Shape.POINT, other = true)), "Right", t); t += 33 }
        assertEquals(OwnerGate.State.STRANGER, gate.state)
    }
}
