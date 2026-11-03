package dev.nihildigit.windowstouch

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * 经 FFM 调 user32 的 GetPointerInfo 与 GetPointerPenInfo。
 *
 * 结构体偏移按 64 位 Windows 的布局手写，x64 与 ARM64 相同。POINTER_INFO 共 96 字节，
 * POINTER_PEN_INFO 在它后面接 penFlags、penMask、pressure、rotation、tiltX、tiltY，共 120 字节。
 * 缓冲区只分配一次、各消息复用，所以实例只能在窗口过程所在的线程上使用。
 */
internal class FfmPointerApi : PointerApi {
    private val arena = Arena.ofAuto()
    private val pointerBuffer = arena.allocate(POINTER_INFO_BYTES)
    private val penBuffer = arena.allocate(POINTER_PEN_INFO_BYTES)

    override fun getPointerInfo(pointerId: Int, out: RawPointerInfo): Boolean {
        if (!call(getPointerInfo, pointerId, pointerBuffer)) return false
        out.pointerType = pointerBuffer.get(JAVA_INT, 0)
        out.flags = pointerBuffer.get(JAVA_INT, 12)
        // Convert ptPixelLocation (physical screen px, offset 32) to the target window's client px via Win32
        // ScreenToClient — the ComposeScene coordinate space. This is DPI- and multi-monitor-correct, unlike deriving
        // the scene origin from AWT's getLocationOnScreen (which mixes physical offsets with logical window insets on
        // per-monitor-DPI displays). hwndTarget is at offset 24 (an HWND, 8 bytes).
        val hwndTarget = pointerBuffer.get(JAVA_LONG, 24)
        if (hwndTarget != 0L) {
            screenToClient.invokeWithArguments(MemorySegment.ofAddress(hwndTarget), pointerBuffer.asSlice(32, 8))
        }
        out.screenX = pointerBuffer.get(JAVA_INT, 32)
        out.screenY = pointerBuffer.get(JAVA_INT, 36)
        out.timeMillis = pointerBuffer.get(JAVA_INT, 64).toLong() and 0xFFFFFFFFL
        return true
    }

    override fun getPointerPenInfo(pointerId: Int, out: RawPenInfo): Boolean {
        if (!call(getPointerPenInfo, pointerId, penBuffer)) return false
        out.penFlags = penBuffer.get(JAVA_INT, 96)
        out.penMask = penBuffer.get(JAVA_INT, 100)
        out.pressure = penBuffer.get(JAVA_INT, 104)
        return true
    }

    private fun call(handle: MethodHandle, pointerId: Int, buffer: MemorySegment): Boolean =
        handle.invokeWithArguments(pointerId, buffer) as Int != 0

    private companion object {
        const val POINTER_INFO_BYTES = 96L
        const val POINTER_PEN_INFO_BYTES = 120L

        val lookup: SymbolLookup = SymbolLookup.libraryLookup("user32", Arena.global())
        val linker: Linker = Linker.nativeLinker()
        val descriptor: FunctionDescriptor = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS)

        val getPointerInfo: MethodHandle = linker.downcallHandle(lookup.find("GetPointerInfo").orElseThrow(), descriptor)
        val getPointerPenInfo: MethodHandle = linker.downcallHandle(lookup.find("GetPointerPenInfo").orElseThrow(), descriptor)
        val screenToClient: MethodHandle =
            linker.downcallHandle(lookup.find("ScreenToClient").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }
}
