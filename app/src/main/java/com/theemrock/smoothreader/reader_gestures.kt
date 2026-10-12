package com.theemrock.smoothreader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope

internal data class ObservedTapGesture(
    val downTimeMillis: Long,
    val upTimeMillis: Long,
    val x: Float,
    val y: Float,
    val maxDistance: Float
)

internal class NonConsumingDoubleTapClassifier(
    private val doubleTapMinTimeMillis: Long,
    private val doubleTapTimeoutMillis: Long,
    private val longPressTimeoutMillis: Long,
    private val touchSlop: Float,
    private val doubleTapSlop: Float
) {
    private var firstTap: ObservedTapGesture? = null

    fun reset() {
        firstTap = null
    }

    fun observe(tap: ObservedTapGesture): Boolean {
        val duration = tap.upTimeMillis - tap.downTimeMillis
        if (duration !in 0..longPressTimeoutMillis || tap.maxDistance > touchSlop) {
            firstTap = null
            return false
        }

        val first = firstTap
        if (first != null) {
            val interval = tap.downTimeMillis - first.upTimeMillis
            val dx = tap.x - first.x
            val dy = tap.y - first.y
            if (interval in doubleTapMinTimeMillis..doubleTapTimeoutMillis &&
                dx * dx + dy * dy <= doubleTapSlop * doubleTapSlop
            ) {
                firstTap = null
                return true
            }
        }

        firstTap = tap
        return false
    }
}

internal suspend fun PointerInputScope.observeDoubleTapsWithoutConsuming(
    doubleTapSlop: Float,
    onDoubleTap: (Offset) -> Unit
) {
    val classifier = NonConsumingDoubleTapClassifier(
        doubleTapMinTimeMillis = viewConfiguration.doubleTapMinTimeMillis,
        doubleTapTimeoutMillis = viewConfiguration.doubleTapTimeoutMillis,
        longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
        touchSlop = viewConfiguration.touchSlop,
        doubleTapSlop = doubleTapSlop
    )
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        var lastChange = down
        var maxDistance = 0f
        var singlePointer = true
        while (lastChange.pressed) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.changes.size != 1) singlePointer = false
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                classifier.reset()
                return@awaitEachGesture
            }
            maxDistance = maxOf(
                maxDistance,
                (change.position - down.position).getDistance()
            )
            lastChange = change
        }

        if (!singlePointer) {
            classifier.reset()
            return@awaitEachGesture
        }
        val tap = ObservedTapGesture(
            downTimeMillis = down.uptimeMillis,
            upTimeMillis = lastChange.uptimeMillis,
            x = lastChange.position.x,
            y = lastChange.position.y,
            maxDistance = maxDistance
        )
        if (classifier.observe(tap)) onDoubleTap(lastChange.position)
    }
}
