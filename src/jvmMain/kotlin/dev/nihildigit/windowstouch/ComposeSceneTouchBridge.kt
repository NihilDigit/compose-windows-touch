@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.nihildigit.windowstouch

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import java.awt.Component
import java.awt.EventQueue
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.Method

/** AWT 把鼠标报成 0 号指针；Windows 的触摸 id 与它不会冲突。 */
private const val HOVERING_CURSOR_POINTER_ID = 0L

/** 窗口过程线程交给事件分发线程的东西，按先后顺序。 */
internal sealed interface TouchCommand {
    data class Inject(val event: TouchEvent) : TouchCommand
    data object Cancel : TouchCommand
}

/**
 * 为事件分发线程排好触摸命令，并合并堆积的 MOVE。
 *
 * AWT 自己会合并排队的鼠标拖动，但每条原生消息各投递一个 Runnable 就不会：分发线程一旦落后，
 * 手指停了之后还会回放过时的位置，界面发黏。所以触点相同的连续 MOVE 折成一条，
 * 较早的位置留作 [TouchPointer.historical]，速度跟踪仍看得到。
 */
internal class TouchCommandQueue {
    private val lock = Any()
    private val pending = ArrayDeque<TouchCommand>()
    private var drainScheduled = false

    /** 返回 true 表示调用方要去安排一次排空。 */
    fun offer(command: TouchCommand): Boolean = synchronized(lock) {
        val last = pending.lastOrNull()
        val merged = if (command is TouchCommand.Inject && last is TouchCommand.Inject) {
            command.event.mergeOlderMove(last.event)
        } else {
            null
        }
        if (merged != null) {
            pending[pending.lastIndex] = TouchCommand.Inject(merged)
        } else {
            pending.addLast(command)
        }
        if (drainScheduled) return false
        drainScheduled = true
        true
    }

    /** 队列空了返回 null，下一次 [offer] 会再要求安排排空。 */
    fun poll(): TouchCommand? = synchronized(lock) {
        pending.removeFirstOrNull().also { if (it == null) drainScheduled = false }
    }
}

/**
 * 把触摸事件注入 [ComposeScene]。
 *
 * Compose Desktop 没有公开的注入入口，所以经反射取它内部的 scene 与方法。Compose 升级后这一步取不到时，
 * [create] 返回 null，调用方继续走 AWT 的鼠标路径。
 */
@OptIn(InternalComposeUiApi::class)
internal class ComposeSceneTouchBridge private constructor(
    private val scene: ComposeScene,
    private val mediator: Any,
    private val contentComponent: Component,
    private val sceneBoundsMethod: Method,
    private val sendPointerEventMethod: Method,
    private val onError: (String, Throwable) -> Unit,
) : AutoCloseable {
    private var closed = false
    private val queue = TouchCommandQueue()

    fun send(event: TouchEvent) {
        if (!closed) submit(TouchCommand.Inject(event))
    }

    fun cancel() {
        if (!closed) submit(TouchCommand.Cancel)
    }

    override fun close() {
        closed = true
    }

    private fun submit(command: TouchCommand) {
        if (EventQueue.isDispatchThread()) {
            execute(command)
            return
        }
        if (queue.offer(command)) EventQueue.invokeLater(::drain)
    }

    private fun drain() {
        while (true) {
            val command = queue.poll() ?: return
            if (closed) continue
            runCatching { execute(command) }.onFailure { onError("触摸事件注入 ComposeScene 失败", it) }
        }
    }

    private fun execute(command: TouchCommand) {
        when (command) {
            is TouchCommand.Inject -> inject(command.event)
            TouchCommand.Cancel -> scene.cancelPointerInput()
        }
    }

    private fun inject(event: TouchEvent) {
        check(contentComponent.isDisplayable) { "Compose 内容已不可显示" }

        // The pointer coordinates are already the target window's client px (converted via ScreenToClient in
        // FfmPointerApi), i.e. the ComposeScene coordinate space. sceneBoundsInPx adds the scene's offset inside the
        // host (usually zero).
        val sceneBounds = sceneBoundsMethod.invoke(mediator) as? Rect ?: Rect.Zero
        fun toScene(clientX: Int, clientY: Int): Offset = screenToScenePosition(clientX, clientY, sceneBounds)

        val pointers = event.pointers.map { pointer ->
            ComposeScenePointer(
                id = PointerId(pointer.id),
                position = toScene(pointer.screenX, pointer.screenY),
                pressed = pointer.pressed,
                type = pointer.kind.toComposePointerType(),
                pressure = pointer.pressure,
                historical = pointer.historical.map { historicalChange(it.eventTimeMillis, toScene(it.screenX, it.screenY)) },
            )
        }

        // 只在第一个触点落下之前：Exit 事件里只有鼠标，而 CMP 会为事件里缺席的每个按下的指针补一个 Release
        if (event.type == TouchEventType.PRESS && pointers.size == 1) {
            exitHoveringCursor(pointers.single().position, event.eventTimeMillis)
        }
        sendPointerEvent(event.type.toComposePointerEventType(), pointers, event.eventTimeMillis)
    }

    /**
     * 一串触摸的第一次注入之前，先结束 AWT 鼠标的悬停。
     *
     * CMP 按 scene 当前的光标输入源解释注入的指针。鼠标还悬停着时，触摸的 PRESS 会被当成 Mouse，
     * 只有 RELEASE 才是 Touch；按下与抬起类型不一致，这次点按被丢弃，所以用过鼠标之后的第一次触摸毫无反应。
     */
    private fun exitHoveringCursor(position: Offset, eventTimeMillis: Long) {
        val cursor = ComposeScenePointer(
            id = PointerId(HOVERING_CURSOR_POINTER_ID),
            position = position,
            pressed = false,
            type = PointerType.Mouse,
            pressure = 0f,
        )
        sendPointerEvent(PointerEventType.Exit.value, listOf(cursor), eventTimeMillis)
    }

    private fun sendPointerEvent(eventType: Int, pointers: List<ComposeScenePointer>, eventTimeMillis: Long) {
        sendPointerEventMethod.invoke(
            scene,
            eventType,
            pointers,
            0, // buttons
            0, // keyboardModifiers
            Offset.Zero.packedValue, // scrollDelta
            eventTimeMillis,
            null, // nativeEvent
            null, // button
            1f, // scaleGestureFactor
            Offset.Zero.packedValue, // panGestureOffset
        )
    }

    companion object {
        fun create(window: Window, onError: (String, Throwable) -> Unit): ComposeSceneTouchBridge? {
            val composeWindow = window as? ComposeWindow ?: return null
            return runCatching {
                val composePanel = ComposeWindow::class.java.getDeclaredField("composePanel").accessible().get(composeWindow)
                val composeContainer = composePanel.javaClass.getDeclaredField("_composeContainer").accessible().get(composePanel)
                    ?: error("Compose 容器尚未初始化")
                val mediator = composeContainer.javaClass.getDeclaredField("mediator").accessible().get(composeContainer)
                val scene = findScene(mediator)
                ComposeSceneTouchBridge(
                    scene = scene,
                    mediator = mediator,
                    contentComponent = composeContainer.javaClass.getDeclaredMethod("getContentComponent").accessible()
                        .invoke(composeContainer) as? Component ?: error("Compose 内容组件尚未初始化"),
                    sceneBoundsMethod = mediator.javaClass.getDeclaredMethod("getSceneBoundsInPx").accessible(),
                    sendPointerEventMethod = findSendPointerEventMethod(scene),
                    onError = onError,
                )
            }.onFailure { onError("取不到 ComposeScene 的内部入口，沿用 AWT 的鼠标输入", it) }.getOrNull()
        }

        private fun findScene(mediator: Any): ComposeScene {
            val mediatorClass = mediator.javaClass
            runCatching {
                val accessor = mediatorClass.getDeclaredMethod("access${'$'}getScene", mediatorClass).accessible()
                accessor.invoke(null, mediator) as? ComposeScene ?: error("scene 访问器返回了意外的值")
            }.getOrNull()?.let { return it }

            val delegate = mediatorClass.getDeclaredField("scene${'$'}delegate").accessible().get(mediator)
            return (delegate as? Lazy<*>)?.value as? ComposeScene ?: error("scene 委托返回了意外的值")
        }

        private fun findSendPointerEventMethod(scene: ComposeScene): Method {
            val methods = (scene.javaClass.methods.asSequence() + ComposeScene::class.java.methods.asSequence())
                .filter { it.name.startsWith("sendPointerEvent-") && it.parameterTypes.size == 10 && it.parameterTypes[1] == List::class.java }
                .distinctBy { it.name }
                .toList()
            check(methods.size == 1) { "应当恰有一个带列表的 sendPointerEvent 重载，实际 ${methods.size} 个" }
            return methods.single().accessible()
        }

        private fun Field.accessible(): Field = apply { trySetAccessible() }
        private fun Method.accessible(): Method = apply { trySetAccessible() }
    }
}

/**
 * Maps a pointer position to [ComposeScene] local coordinates. The input is already the target window's client px
 * (converted via `ScreenToClient` in [FfmPointerApi]), which equals the scene's px space; [sceneBoundsInPx] only adds
 * the scene's offset inside the host (usually zero). DPI- and multi-monitor-correct by construction.
 */
internal fun screenToScenePosition(
    clientX: Int,
    clientY: Int,
    sceneBoundsInPx: Rect,
): Offset = Offset(
    clientX - sceneBoundsInPx.left,
    clientY - sceneBoundsInPx.top,
)

/**
 * 速度跟踪器经内部的 originalEventPosition 读历史采样，公开构造器把它留在原点。桌面 scene 对我们的采样
 * 原样放行，用公开构造器造出来的采样就成了从原点起跳，fling 速度大得离谱。所以照 ComposeScenePointer
 * 给当前位置设值的做法，把它设成采样的位置。
 */
internal fun historicalChange(uptimeMillis: Long, scenePosition: Offset): HistoricalChange = HistoricalChange(
    uptimeMillis = uptimeMillis,
    position = scenePosition,
    scaleFactor = 1f,
    panOffset = Offset.Zero,
    originalEventPosition = scenePosition,
)

private fun TouchEventType.toComposePointerEventType(): Int = when (this) {
    TouchEventType.PRESS -> PointerEventType.Press.value
    TouchEventType.MOVE -> PointerEventType.Move.value
    TouchEventType.RELEASE -> PointerEventType.Release.value
}

private fun PointerKind.toComposePointerType(): PointerType = when (this) {
    PointerKind.TOUCH -> PointerType.Touch
    PointerKind.STYLUS -> PointerType.Stylus
    PointerKind.ERASER -> PointerType.Eraser
}
