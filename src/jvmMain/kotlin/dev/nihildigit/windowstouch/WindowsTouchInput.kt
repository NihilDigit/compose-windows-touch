package dev.nihildigit.windowstouch

import java.awt.Window

/**
 * 让 Compose Desktop 窗口收到真正的触摸与笔输入。
 *
 * AWT 不处理 WM_POINTER，Windows 把触摸降级成鼠标，Compose 看到的永远是单个鼠标指针：没有多指，
 * 没有压力，笔也丢了类型。这里在窗口过程里截下 WM_POINTER*，直接注入 Compose 的 scene。
 *
 * 不替宿主挂窗口过程：宿主一般已经子类化了自己的窗口，再抢一遍会互相覆盖。宿主在自己的窗口过程里，
 * 对框架窗口与 Skiko 的画布子窗口收到的每条消息先问一遍 [handleMessage]，返回 true 就当场返回 0。
 * 须在窗口过程所在的线程上调用，这也是 FFM 缓冲区复用所依赖的。
 *
 * 与 Compose 内部的对接在第一条指针消息到来时才建立：此时窗口已经显示，容器一定初始化完了。
 * 建立失败（Compose 升级改了内部结构）就一直退回 AWT 的鼠标路径，只报一次。
 */
class WindowsTouchInput private constructor(
    private val window: Window,
    private val api: PointerApi,
    private val onError: (String, Throwable) -> Unit,
) : AutoCloseable {
    private var handler: PointerInputHandler? = null
    private var bridge: ComposeSceneTouchBridge? = null
    private var unavailable = false

    /** 返回 true 表示这条消息已被消费，窗口过程应返回 0，不再交给 DefWindowProc。 */
    fun handleMessage(message: Int, wParam: Long): Boolean {
        if (!isPointerMessage(message)) return false
        return (handler ?: createHandler() ?: return false).handleMessage(message, wParam)
    }

    private fun createHandler(): PointerInputHandler? {
        if (unavailable) return null
        val newBridge = ComposeSceneTouchBridge.create(window, onError)
        if (newBridge == null) {
            unavailable = true
            return null
        }
        bridge = newBridge
        return PointerInputHandler(api, newBridge::send, newBridge::cancel, onError).also { handler = it }
    }

    override fun close() {
        handler?.close()
        bridge?.close()
    }

    companion object {
        /** 非 Windows 上返回 null。[onError] 收到的是写给日志的说明与异常。 */
        fun create(window: Window, onError: (String, Throwable) -> Unit = { _, _ -> }): WindowsTouchInput? {
            if (!System.getProperty("os.name").startsWith("Windows")) return null
            return runCatching { WindowsTouchInput(window, FfmPointerApi(), onError) }
                .onFailure { onError("无法加载 user32 的指针接口", it) }
                .getOrNull()
        }
    }
}
