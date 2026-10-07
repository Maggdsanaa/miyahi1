package com.example.persontracker.tracking

import com.example.persontracker.domain.BoxF
import com.example.persontracker.domain.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TrackingTest {

    private val rnd = Random(1)

    private fun det(cx: Float, cy: Float, w: Float = 0.1f, h: Float = 0.3f, score: Float = 0.8f): Detection {
        fun n() = (rnd.nextGaussian() * 0.004).toFloat()
        return Detection(BoxF.fromCenter(cx + n(), cy + n(), w, h), score)
    }

    @Test
    fun hungarian_findsOptimalAssignment() {
        val cost = arrayOf(
            floatArrayOf(4f, 1f, 3f),
            floatArrayOf(2f, 0f, 5f),
            floatArrayOf(3f, 2f, 2f),
        )
        val r = Hungarian.solve(cost)
        val total = r.indices.sumOf { cost[it][r[it]].toDouble() }
        assertEquals(5.0, total, 1e-6) // (0->1)=1, (1->0)=2, (2->2)=2
    }

    @Test
    fun hungarian_handlesMoreRowsThanColumns() {
        val cost = arrayOf(floatArrayOf(1f), floatArrayOf(0f), floatArrayOf(2f))
        val r = Hungarian.solve(cost)
        assertEquals(listOf(-1, 0, -1), r.toList())
    }

    @Test
    fun singlePerson_keepsSameId() {
        val tracker = ByteTracker()
        val ids = HashSet<Int>()
        for (f in 0 until 40) {
            tracker.update(listOf(det(0.2f + 0.015f * f, 0.5f)), f * 100L).forEach { ids.add(it.id) }
        }
        assertEquals(setOf(1), ids)
    }

    @Test
    fun twoPeople_getDistinctStableIds_evenWithMissedDetections() {
        val tracker = ByteTracker()
        val seen = HashMap<Int, Int>()
        for (f in 0 until 60) {
            val dets = ArrayList<Detection>()
            dets.add(det(0.2f + 0.01f * f, 0.5f, score = 0.85f))
            if (f !in 25..29) dets.add(det(0.8f - 0.01f * f, 0.5f, score = if (f % 7 != 0) 0.8f else 0.3f))
            tracker.update(dets, f * 100L).forEach { seen.merge(it.id, 1, Int::plus) }
        }
        assertEquals(setOf(1, 2), seen.keys)
    }

    @Test
    fun shortGap_keepsId_longGap_createsNewId() {
        val tracker = ByteTracker()
        val first = HashSet<Int>()
        for (f in 0 until 10) tracker.update(listOf(det(0.5f, 0.5f)), f * 100L).forEach { first.add(it.id) }
        for (f in 10 until 25) tracker.update(emptyList(), f * 100L)             // 1.5 s غياب
        for (f in 25 until 30) tracker.update(listOf(det(0.5f, 0.5f)), f * 100L).forEach { first.add(it.id) }
        assertEquals(setOf(1), first)

        for (f in 30 until 60) tracker.update(emptyList(), f * 100L)             // 3 s غياب
        val second = HashSet<Int>()
        for (f in 60 until 66) tracker.update(listOf(det(0.5f, 0.5f)), f * 100L).forEach { second.add(it.id) }
        assertEquals(setOf(2), second)
    }

    @Test
    fun singleFrameFalsePositive_isNeverShown() {
        val tracker = ByteTracker()
        assertTrue(tracker.update(listOf(det(0.3f, 0.3f, 0.1f, 0.2f, 0.9f)), 0).isEmpty())
        assertTrue(tracker.update(emptyList(), 100).isEmpty())
    }
}
