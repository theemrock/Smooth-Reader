package com.theemrock.smoothreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDoubleTapTest {
    private fun classifier() = NonConsumingDoubleTapClassifier(
        doubleTapMinTimeMillis = 40,
        doubleTapTimeoutMillis = 300,
        longPressTimeoutMillis = 500,
        touchSlop = 10f
    )

    @Test fun twoShortNearbyTapsProduceExactlyOneDoubleTap() {
        val classifier = classifier()

        assertFalse(classifier.observe(tap(down = 0, up = 50, x = 20f, y = 30f)))
        assertTrue(classifier.observe(tap(down = 120, up = 170, x = 23f, y = 34f)))
        assertFalse(classifier.observe(tap(down = 220, up = 260, x = 23f, y = 34f)))
    }

    @Test fun dragGestureCannotCombineWithATap() {
        val classifier = classifier()

        assertFalse(classifier.observe(tap(down = 0, up = 40)))
        assertFalse(classifier.observe(tap(down = 100, up = 150, maxDistance = 11f)))
        assertFalse(classifier.observe(tap(down = 200, up = 240)))
    }

    @Test fun longPressCannotCombineWithATap() {
        val classifier = classifier()

        assertFalse(classifier.observe(tap(down = 0, up = 40)))
        assertFalse(classifier.observe(tap(down = 100, up = 601)))
        assertFalse(classifier.observe(tap(down = 650, up = 690)))
    }

    @Test fun tapsOutsideTimeOrPositionToleranceAreNotDoubleTaps() {
        val timeout = classifier()
        assertFalse(timeout.observe(tap(down = 0, up = 40)))
        assertFalse(timeout.observe(tap(down = 400, up = 440)))

        val distance = classifier()
        assertFalse(distance.observe(tap(down = 0, up = 40, x = 0f, y = 0f)))
        assertFalse(distance.observe(tap(down = 100, up = 140, x = 25f, y = 0f)))
    }

    private fun tap(
        down: Long,
        up: Long,
        x: Float = 20f,
        y: Float = 30f,
        maxDistance: Float = 0f
    ) = ObservedTapGesture(down, up, x, y, maxDistance)
}
