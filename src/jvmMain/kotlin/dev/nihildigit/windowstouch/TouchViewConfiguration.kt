package dev.nihildigit.windowstouch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Android 的 ViewConfiguration.TOUCH_SLOP。Compose Desktop 写死 18dp（ComposeSceneMediator 里的
 * DesktopViewConfiguration，是 AndroidX 的默认值），因为桌面原本只有鼠标；有了真触摸，手指要挪过 18dp
 * 才算拖动，滚动发黏。
 */
private val TOUCH_SLOP = 8.dp

/**
 * 给内容提供与 Android 一致的触摸阈值。
 *
 * 鼠标的阈值是触摸阈值乘一个固定比例（0.125dp / 18dp），换成 8dp 后从 0.125dp 变成约 0.056dp，
 * 两者都不到一个物理像素，鼠标的手感不变。每个 Window 有自己的 scene 与 ViewConfiguration，
 * 所以要分别包住每个窗口的内容。
 */
@Composable
fun ProvideTouchViewConfiguration(content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val density = LocalDensity.current
    val configuration = remember(base, density) { TouchViewConfiguration(base, density) }
    CompositionLocalProvider(LocalViewConfiguration provides configuration, content = content)
}

private class TouchViewConfiguration(
    private val base: ViewConfiguration,
    density: Density,
) : ViewConfiguration by base {
    override val touchSlop: Float = with(density) { TOUCH_SLOP.toPx() }
}
