package dev.nihildigit.windowstouch

internal const val PT_TOUCH: Int = 0x00000002
internal const val PT_PEN: Int = 0x00000003

internal const val WM_POINTERUPDATE: Int = 0x0245
internal const val WM_POINTERDOWN: Int = 0x0246
internal const val WM_POINTERUP: Int = 0x0247
internal const val WM_POINTERCAPTURECHANGED: Int = 0x024C

internal const val POINTER_FLAG_INCONTACT: Int = 0x00000004
internal const val POINTER_FLAG_CANCELED: Int = 0x00008000

internal const val PEN_FLAG_INVERTED: Int = 0x00000002
internal const val PEN_FLAG_ERASER: Int = 0x00000004
internal const val PEN_MASK_PRESSURE: Int = 0x00000001

/** Windows 报告的笔压范围是 0..1024。 */
internal const val PEN_PRESSURE_MAX: Float = 1024f

internal enum class PointerMessage { DOWN, UPDATE, UP }

internal enum class TouchEventType { PRESS, MOVE, RELEASE }

/** 一个触点以什么类型交给 Compose。 */
internal enum class PointerKind { TOUCH, STYLUS, ERASER }

internal data class PointerData(
    val id: Long,
    val pointerType: Int,
    val flags: Int,
    val screenX: Int,
    val screenY: Int,
    val eventTimeMillis: Long = 0L,
    val kind: PointerKind = PointerKind.TOUCH,
    val pressure: Float = 1f,
) {
    val isInContact: Boolean get() = flags and POINTER_FLAG_INCONTACT != 0
    val isCanceled: Boolean get() = flags and POINTER_FLAG_CANCELED != 0
}

/** 触点的一次屏幕坐标采样；合并多条 MOVE 时留下较早的几次。 */
internal data class TouchSample(
    val eventTimeMillis: Long,
    val screenX: Int,
    val screenY: Int,
)

/** 一个事件之后 Compose 应当看到的某个触点的状态。 */
internal data class TouchPointer(
    val id: Long,
    val screenX: Int,
    val screenY: Int,
    val pressed: Boolean,
    val kind: PointerKind,
    val pressure: Float,
    val historical: List<TouchSample> = emptyList(),
) {
    fun toSample(eventTimeMillis: Long): TouchSample = TouchSample(eventTimeMillis, screenX, screenY)
}

/**
 * 一条原生消息翻译成的事件。
 *
 * [pointers] 是全部在跟踪的触点：ComposeScene 要求每个事件都带上当前所有指针。松开的触点在
 * RELEASE 里以 `pressed = false` 再列一次，之后才丢掉。
 */
internal data class TouchEvent(
    val type: TouchEventType,
    val pointer: PointerData,
    val pointers: List<TouchPointer>,
) {
    val eventTimeMillis: Long get() = pointer.eventTimeMillis

    /**
     * 把更早的一条 MOVE [previous] 并进本条的历史。两条的触点集合不同时返回 null。
     */
    fun mergeOlderMove(previous: TouchEvent): TouchEvent? {
        if (type != TouchEventType.MOVE || previous.type != TouchEventType.MOVE) return null
        if (previous.pointers.size != pointers.size) return null
        val previousById = previous.pointers.associateBy { it.id }
        val merged = pointers.map { current ->
            val older = previousById[current.id] ?: return null
            current.copy(historical = older.historical + older.toSample(previous.eventTimeMillis))
        }
        return copy(pointers = merged)
    }
}

internal sealed interface PointerDispatch {
    data object Pass : PointerDispatch
    data object Consume : PointerDispatch
    data object Cancel : PointerDispatch
    data class Send(val event: TouchEvent) : PointerDispatch
}

/**
 * 跟踪窗口上所有手指与笔的触点，决定 Compose 该看到什么。
 *
 * 每个接触中的 DOWN 开始跟踪一个指针，此后每个事件都带上全部触点，双指捏合之类的手势才能完整到达 Compose。
 *
 * 一个指针的 DOWN 一旦被消费，它后面的消息也必须全部消费，否则 DefWindowProc 会为它合成鼠标输入，
 * 同一次操作到达两遍。取消之后，其余触点记在 [suppressed] 里直到抬起，但不再注入：
 * `cancelPointerInput` 之后的输入在 Compose 看来是新指针。
 *
 * 悬停的笔（未跟踪的指针上不带 INCONTACT 的 UPDATE）放行，Windows 才会继续为它合成鼠标移动，悬停光标照常。
 */
internal class PointerContactTracker {
    private val contacts = LinkedHashMap<Long, PointerData>()
    private val suppressed = mutableSetOf<Long>()

    val trackedPointerIds: Set<Long> get() = contacts.keys
    val suppressedPointerIds: Set<Long> get() = suppressed

    val isActive: Boolean
        get() = contacts.isNotEmpty() || suppressed.isNotEmpty()

    fun owns(pointerId: Long): Boolean = pointerId in contacts || pointerId in suppressed

    fun handle(message: PointerMessage, pointer: PointerData): PointerDispatch {
        if (pointer.pointerType != PT_TOUCH && pointer.pointerType != PT_PEN) return PointerDispatch.Pass

        if (pointer.isCanceled) {
            return if (owns(pointer.id)) cancel(endedPointerId = pointer.id.takeIf { message == PointerMessage.UP })
            else PointerDispatch.Pass
        }

        // Windows 会复用指针 id。DOWN 一定是新触点，不能被上一个触点留下的抑制吞掉：
        // 上一个触点的 UP 可能发给了别的窗口。
        if (message != PointerMessage.DOWN && pointer.id in suppressed) {
            if (message == PointerMessage.UP) suppressed -= pointer.id
            return PointerDispatch.Consume
        }

        return when (message) {
            PointerMessage.DOWN -> {
                if (pointer.id in contacts) return PointerDispatch.Consume
                suppressed -= pointer.id
                contacts[pointer.id] = pointer
                send(TouchEventType.PRESS, pointer)
            }

            PointerMessage.UPDATE -> {
                if (pointer.id !in contacts) return PointerDispatch.Pass
                // 没有 UP 就失去了接触：结束这个指针，不让它一直按着
                if (!pointer.isInContact) return release(pointer)
                contacts[pointer.id] = pointer
                send(TouchEventType.MOVE, pointer)
            }

            PointerMessage.UP -> {
                if (pointer.id !in contacts) return PointerDispatch.Pass
                release(pointer)
            }
        }
    }

    fun handleCaptureChanged(): PointerDispatch {
        return if (contacts.isNotEmpty()) cancel(endedPointerId = null) else PointerDispatch.Pass
    }

    fun handleReadFailure(pointerId: Long): PointerDispatch {
        return if (pointerId in contacts) cancel(endedPointerId = pointerId) else PointerDispatch.Pass
    }

    /** 忘掉所有指针。返回 Compose 是否有触点需要取消。 */
    fun clear(): Boolean {
        val hadContacts = contacts.isNotEmpty()
        contacts.clear()
        suppressed.clear()
        return hadContacts
    }

    private fun release(pointer: PointerData): PointerDispatch {
        contacts[pointer.id] = pointer
        val dispatch = send(TouchEventType.RELEASE, pointer)
        contacts.remove(pointer.id)
        return dispatch
    }

    private fun send(type: TouchEventType, changed: PointerData): PointerDispatch {
        val pointers = contacts.values.map { contact ->
            TouchPointer(
                id = contact.id,
                screenX = contact.screenX,
                screenY = contact.screenY,
                pressed = !(type == TouchEventType.RELEASE && contact.id == changed.id),
                kind = contact.kind,
                pressure = contact.pressure,
            )
        }
        return PointerDispatch.Send(TouchEvent(type, changed, pointers))
    }

    /**
     * 取消整个交互。除 [endedPointerId] 外，在跟踪的触点继续被消费直到抬起；
     * [endedPointerId] 已经抬起或读不出来，不会再有值得等的消息。
     */
    private fun cancel(endedPointerId: Long?): PointerDispatch {
        suppressed += contacts.keys
        contacts.clear()
        if (endedPointerId != null) suppressed -= endedPointerId
        return PointerDispatch.Cancel
    }
}
