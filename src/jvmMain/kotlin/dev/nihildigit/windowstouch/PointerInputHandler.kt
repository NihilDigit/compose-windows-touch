package dev.nihildigit.windowstouch

/** GetPointerInfo 读出的字段。实例在消息之间复用，热路径上不分配。 */
internal class RawPointerInfo {
    var pointerType: Int = 0
    var flags: Int = 0
    var screenX: Int = 0
    var screenY: Int = 0

    /** dwTime，即该消息生成时的 GetTickCount，按无符号 32 位存放。 */
    var timeMillis: Long = 0L
}

/** GetPointerPenInfo 读出的字段。倾斜与旋转在 Compose 里没有对应物，不读。 */
internal class RawPenInfo {
    var penFlags: Int = 0
    var penMask: Int = 0
    var pressure: Int = 0
}

/**
 * 读取原生指针信息的那一层。核心逻辑只认这个接口，所以原生调用经 FFM、JNA 还是别的方式都不影响它。
 * 读取失败（指针已经消失）返回 false。
 */
internal interface PointerApi {
    fun getPointerInfo(pointerId: Int, out: RawPointerInfo): Boolean
    fun getPointerPenInfo(pointerId: Int, out: RawPenInfo): Boolean
}

internal fun pointerIdFromWParam(wParam: Long): Long = wParam and 0xFFFF

internal fun isPointerMessage(message: Int): Boolean = when (message) {
    WM_POINTERDOWN, WM_POINTERUPDATE, WM_POINTERUP, WM_POINTERCAPTURECHANGED -> true
    else -> false
}

/**
 * 把原生指针消息翻译成 [TouchEvent]，交给唯一的一个 [PointerContactTracker]。
 *
 * 不处理 WM_NCPOINTER*：落在非客户区的触摸（标题栏、边框）该由 DefWindowProc 合成鼠标消息，
 * 窗口移动、缩放与标题栏按钮才照常工作。
 *
 * 实例只在窗口过程所在的线程上使用。
 */
internal class PointerInputHandler(
    private val api: PointerApi,
    private val dispatch: (TouchEvent) -> Unit,
    private val cancel: () -> Unit,
    private val onError: (String, Throwable) -> Unit,
) : AutoCloseable {
    private val tracker = PointerContactTracker()
    private var closed = false
    private var disabled = false

    private val pointerInfo = RawPointerInfo()
    private val penInfo = RawPenInfo()

    /** 返回 true 表示消息已处理，窗口过程不应再交给 DefWindowProc。 */
    fun handleMessage(message: Int, wParam: Long): Boolean {
        if (closed || disabled) return false
        val pointerId = pointerIdFromWParam(wParam)
        val ownedBeforeHandling = if (message == WM_POINTERCAPTURECHANGED) tracker.isActive else tracker.owns(pointerId)

        return try {
            when (message) {
                WM_POINTERCAPTURECHANGED -> apply(tracker.handleCaptureChanged())
                WM_POINTERDOWN, WM_POINTERUPDATE, WM_POINTERUP -> handlePointerMessage(message, pointerId)
                else -> false
            }
        } catch (error: Throwable) {
            onError("Windows 触摸输入出错，停用原生触摸", error)
            tracker.clear()
            runCatching(cancel)
            disabled = true
            // 出错的这一条若属于已接管的序列仍要消费，哪怕取消或注入本身失败：否则同一次触摸会再被合成成一遍鼠标输入。
            // 停用之后的后续消息则放行：DefWindowProc 只会为它们合成鼠标的移动与抬起，没有按下，无害
            ownedBeforeHandling
        }
    }

    private fun handlePointerMessage(message: Int, pointerId: Long): Boolean {
        if (!api.getPointerInfo(pointerId.toInt(), pointerInfo)) {
            return apply(tracker.handleReadFailure(pointerId))
        }
        val kind = when (message) {
            WM_POINTERDOWN -> PointerMessage.DOWN
            WM_POINTERUPDATE -> PointerMessage.UPDATE
            else -> PointerMessage.UP
        }
        val data = if (pointerInfo.pointerType == PT_PEN) readPen(pointerId) else pointerData(pointerId)
        return apply(tracker.handle(kind, data))
    }

    private fun readPen(pointerId: Long): PointerData {
        // 笔的细节读不出来时仍是一个可用的触笔触点，只是没有压力
        if (!api.getPointerPenInfo(pointerId.toInt(), penInfo)) {
            return pointerData(pointerId, kind = PointerKind.STYLUS)
        }
        val kind = if (penInfo.penFlags and (PEN_FLAG_INVERTED or PEN_FLAG_ERASER) != 0) {
            PointerKind.ERASER
        } else {
            PointerKind.STYLUS
        }
        val pressure = if (penInfo.penMask and PEN_MASK_PRESSURE != 0) {
            (penInfo.pressure / PEN_PRESSURE_MAX).coerceIn(0f, 1f)
        } else {
            1f
        }
        return pointerData(pointerId, kind, pressure)
    }

    private fun pointerData(
        pointerId: Long,
        kind: PointerKind = PointerKind.TOUCH,
        pressure: Float = 1f,
    ) = PointerData(
        id = pointerId,
        pointerType = pointerInfo.pointerType,
        flags = pointerInfo.flags,
        screenX = pointerInfo.screenX,
        screenY = pointerInfo.screenY,
        eventTimeMillis = pointerInfo.timeMillis,
        kind = kind,
        pressure = pressure,
    )

    private fun apply(result: PointerDispatch): Boolean = when (result) {
        PointerDispatch.Pass -> false
        PointerDispatch.Consume -> true
        PointerDispatch.Cancel -> {
            cancel()
            true
        }

        is PointerDispatch.Send -> {
            dispatch(result.event)
            true
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (tracker.clear()) runCatching(cancel)
    }
}
