package dev.nihildigit.windowstouch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class ScriptedPointerApi : PointerApi {
    class Script(val info: RawPointerInfo, val pen: RawPenInfo?)

    val scripts = mutableMapOf<Int, Script>()

    override fun getPointerInfo(pointerId: Int, out: RawPointerInfo): Boolean {
        val script = scripts[pointerId] ?: return false
        out.pointerType = script.info.pointerType
        out.flags = script.info.flags
        out.screenX = script.info.screenX
        out.screenY = script.info.screenY
        out.timeMillis = script.info.timeMillis
        return true
    }

    override fun getPointerPenInfo(pointerId: Int, out: RawPenInfo): Boolean {
        val pen = scripts[pointerId]?.pen ?: return false
        out.penFlags = pen.penFlags
        out.penMask = pen.penMask
        out.pressure = pen.pressure
        return true
    }
}

private class HandlerFixture {
    val api = ScriptedPointerApi()
    val events = mutableListOf<TouchEvent>()
    val errors = mutableListOf<String>()
    var failDispatch = false
    var cancels = 0
    val handler = PointerInputHandler(
        api = api,
        dispatch = {
            if (failDispatch) error("dispatch 失败")
            events += it
        },
        cancel = { cancels++ },
        onError = { message, _ -> errors += message },
    )

    /** 先让假 API 报出这个指针的状态，再投递消息。 */
    fun send(
        message: Int,
        id: Int,
        x: Int = 0,
        type: Int = PT_TOUCH,
        flags: Int = POINTER_FLAG_INCONTACT,
        pen: RawPenInfo? = null,
    ): Boolean {
        val info = RawPointerInfo().also {
            it.pointerType = type
            it.flags = flags
            it.screenX = x
        }
        api.scripts[id] = ScriptedPointerApi.Script(info, pen)
        return handler.handleMessage(message, id.toLong())
    }
}

class PointerInputHandlerTest {
    @Test
    fun multiTouchEventsCarryEveryContact() {
        val f = HandlerFixture()
        f.send(WM_POINTERDOWN, 1, x = 10)
        f.send(WM_POINTERDOWN, 2, x = 20)
        f.send(WM_POINTERUPDATE, 1, x = 11)
        f.send(WM_POINTERUP, 2, x = 20, flags = 0)

        assertEquals(
            listOf(TouchEventType.PRESS, TouchEventType.PRESS, TouchEventType.MOVE, TouchEventType.RELEASE),
            f.events.map { it.type },
        )
        assertEquals(listOf(1L), f.events[0].pointers.map { it.id })
        assertEquals(listOf(1L, 2L), f.events[1].pointers.map { it.id })
        assertEquals(listOf(1L, 2L), f.events[2].pointers.map { it.id })
        // 松开的触点在 RELEASE 里以 pressed=false 再出现一次，另一个仍按着
        assertEquals(listOf(true, false), f.events[3].pointers.map { it.pressed })
    }

    @Test
    fun cancelKeepsConsumingRemainingContactsUntilLiftedWithoutInjecting() {
        val f = HandlerFixture()
        f.send(WM_POINTERDOWN, 1)
        f.send(WM_POINTERDOWN, 2)
        val eventsBefore = f.events.size

        assertTrue(f.handler.handleMessage(WM_POINTERCAPTURECHANGED, 0))
        assertEquals(1, f.cancels)

        assertTrue(f.send(WM_POINTERUPDATE, 1, x = 5))
        assertTrue(f.send(WM_POINTERUP, 1, flags = 0))
        assertTrue(f.send(WM_POINTERUP, 2, flags = 0))
        assertEquals(eventsBefore, f.events.size)

        // 全部抬起后不再接管，Windows 才能为之后的输入正常合成消息
        assertFalse(f.send(WM_POINTERUPDATE, 2, x = 5))
    }

    @Test
    fun reusedPointerIdStartsNewPressAfterCancel() {
        val f = HandlerFixture()
        f.send(WM_POINTERDOWN, 1)
        f.handler.handleMessage(WM_POINTERCAPTURECHANGED, 0)
        // 旧触点的 UP 没有送到这个窗口，id 1 被系统复用
        assertTrue(f.send(WM_POINTERDOWN, 1, x = 7))

        assertEquals(TouchEventType.PRESS, f.events.last().type)
        assertEquals(7, f.events.last().pointers.single().screenX)
    }

    @Test
    fun updateWithoutContactReleasesPointer() {
        val f = HandlerFixture()
        f.send(WM_POINTERDOWN, 1)
        assertTrue(f.send(WM_POINTERUPDATE, 1, flags = 0))

        assertEquals(TouchEventType.RELEASE, f.events.last().type)
        assertFalse(f.events.last().pointers.single().pressed)
        // 已经结束，后续同 id 的消息不再属于我们
        assertFalse(f.send(WM_POINTERUPDATE, 1, flags = 0))
    }

    @Test
    fun hoveringPenPassesThrough() {
        val f = HandlerFixture()
        assertFalse(f.send(WM_POINTERUPDATE, 3, type = PT_PEN, flags = 0))
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun penEraserAndInvertedFlagsMapToEraserKind() {
        val f = HandlerFixture()
        f.send(WM_POINTERDOWN, 3, type = PT_PEN, pen = RawPenInfo().also { it.penFlags = PEN_FLAG_ERASER })
        f.send(WM_POINTERDOWN, 4, type = PT_PEN, pen = RawPenInfo().also { it.penFlags = PEN_FLAG_INVERTED })
        f.send(WM_POINTERDOWN, 5, type = PT_PEN, pen = RawPenInfo())

        assertEquals(
            listOf(PointerKind.ERASER, PointerKind.ERASER, PointerKind.STYLUS),
            f.events.last().pointers.map { it.kind },
        )
    }

    @Test
    fun penPressureIsNormalizedClampedAndDefaultsToOneWithoutMask() {
        fun pressureOf(mask: Int, raw: Int): Float {
            val f = HandlerFixture()
            f.send(
                WM_POINTERDOWN, 3, type = PT_PEN,
                pen = RawPenInfo().also {
                    it.penMask = mask
                    it.pressure = raw
                },
            )
            return f.events.single().pointers.single().pressure
        }

        assertEquals(0.5f, pressureOf(PEN_MASK_PRESSURE, 512))
        assertEquals(1f, pressureOf(PEN_MASK_PRESSURE, 4000))
        // 驱动没报压力时，raw 里的残值不能当压力
        assertEquals(1f, pressureOf(0, 100))
    }

    @Test
    fun nonClientPointerMessagesAreNotHandled() {
        val f = HandlerFixture()
        f.send(WM_POINTERDOWN, 1)
        // WM_NCPOINTERUPDATE、DOWN、UP；即使指针正被跟踪也要交还 DefWindowProc
        for (message in listOf(0x0241, 0x0242, 0x0243)) {
            assertFalse(f.send(message, 1))
        }
        assertEquals(1, f.events.size)
        assertEquals(0, f.cancels)
    }

    @Test
    fun dispatchFailureDisablesHandlerButStillConsumesOwnedPointer() {
        val f = HandlerFixture()
        f.send(WM_POINTERDOWN, 1)
        f.failDispatch = true

        assertTrue(f.send(WM_POINTERUPDATE, 1, x = 5))
        assertEquals(1, f.errors.size)
        assertEquals(1, f.cancels)
        // 停用后不再处理任何消息，也不再重复报错
        assertFalse(f.send(WM_POINTERDOWN, 2))
        assertEquals(1, f.errors.size)
    }
}
