@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.nihildigit.windowstouch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun pointer(id: Long, x: Int, pressed: Boolean = true) =
    TouchPointer(id, x, 0, pressed, PointerKind.TOUCH, 1f)

private fun event(type: TouchEventType, time: Long, vararg pointers: TouchPointer) = TouchEvent(
    type = type,
    pointer = PointerData(pointers.first().id, PT_TOUCH, POINTER_FLAG_INCONTACT, pointers.first().screenX, 0, time),
    pointers = pointers.toList(),
)

class TouchCommandQueueTest {
    @Test
    fun consecutiveMovesWithSameContactsMergeAndKeepHistory() {
        val queue = TouchCommandQueue()
        assertTrue(queue.offer(TouchCommand.Inject(event(TouchEventType.MOVE, 10, pointer(1, 1)))))
        queue.offer(TouchCommand.Inject(event(TouchEventType.MOVE, 20, pointer(1, 2))))
        queue.offer(TouchCommand.Inject(event(TouchEventType.MOVE, 30, pointer(1, 3))))

        val merged = (queue.poll() as TouchCommand.Inject).event.pointers.single()
        assertEquals(3, merged.screenX)
        assertEquals(listOf(10L to 1, 20L to 2), merged.historical.map { it.eventTimeMillis to it.screenX })
        assertNull(queue.poll())
    }

    @Test
    fun movesWithDifferentContactSetsAreNotMerged() {
        val queue = TouchCommandQueue()
        queue.offer(TouchCommand.Inject(event(TouchEventType.MOVE, 10, pointer(1, 1))))
        queue.offer(TouchCommand.Inject(event(TouchEventType.MOVE, 20, pointer(1, 2), pointer(2, 5))))

        assertTrue(queue.poll() is TouchCommand.Inject)
        assertTrue(queue.poll() is TouchCommand.Inject)
        assertNull(queue.poll())
    }

    @Test
    fun cancelAndNonMoveEventsAreNeverMerged() {
        val queue = TouchCommandQueue()
        queue.offer(TouchCommand.Inject(event(TouchEventType.MOVE, 10, pointer(1, 1))))
        queue.offer(TouchCommand.Cancel)
        queue.offer(TouchCommand.Inject(event(TouchEventType.MOVE, 20, pointer(1, 2))))
        queue.offer(TouchCommand.Inject(event(TouchEventType.RELEASE, 30, pointer(1, 2, pressed = false))))

        assertTrue(queue.poll() is TouchCommand.Inject)
        assertEquals(TouchCommand.Cancel, queue.poll())
        assertTrue(queue.poll() is TouchCommand.Inject)
        assertEquals(TouchEventType.RELEASE, (queue.poll() as TouchCommand.Inject).event.type)
        assertNull(queue.poll())
    }

    @Test
    fun drainIsRequestedOnlyOncePerBurst() {
        val queue = TouchCommandQueue()
        assertTrue(queue.offer(TouchCommand.Cancel))
        assertFalse(queue.offer(TouchCommand.Cancel))
        queue.poll()
        queue.poll()
        assertNull(queue.poll())
        // 排空到 null 之后，下一条要重新要求安排
        assertTrue(queue.offer(TouchCommand.Cancel))
    }

    @Test
    fun screenPositionScalesHostLocationByDensityAndSubtractsSceneOffset() {
        // 宿主位置是 AWT 的逻辑坐标，要乘密度才与屏幕像素同单位
        val position = screenToScenePosition(500, 400, Point(100, 50), 2f, Rect(10f, 20f, 0f, 0f))
        assertEquals(Offset(290f, 280f), position)
    }

    @Test
    fun historicalChangeOriginalPositionMatchesPosition() {
        // 速度跟踪器读 originalEventPosition，留在原点会算出离谱的 fling 速度
        val change = historicalChange(5, Offset(3f, 4f))
        assertEquals(change.position, change.originalEventPosition)
    }
}
