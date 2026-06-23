package kr.co.donghyun.flamelauncher.presentation.util.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class DeviceClass { Phone, Tablet }

/**
 * smallestScreenWidthDp 기준. 회전과 무관해서 안정적.
 *  - 폰 (portrait sw ~360-400dp)  → Phone
 *  - 7" 태블릿 (sw 600~)           → Tablet
 *  - 10"+ 태블릿 (sw 720~)         → Tablet
 */
@Composable
@ReadOnlyComposable
fun rememberDeviceClass(): DeviceClass {
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    return if (sw >= 600) DeviceClass.Tablet else DeviceClass.Phone
}

@Composable
@ReadOnlyComposable
fun isTablet(): Boolean = rememberDeviceClass() == DeviceClass.Tablet

/** 화면 공통 디멘션 — 폰/태블릿 분기 */
object ResponsiveDimens {
    @Composable fun contentMaxWidth(): Dp = if (isTablet()) 1200.dp else Dp.Unspecified
    @Composable fun horizontalPadding(): Dp = if (isTablet()) 24.dp else 16.dp
    @Composable fun listGap(): Dp = if (isTablet()) 10.dp else 6.dp
    @Composable fun sectionGap(): Dp = if (isTablet()) 20.dp else 16.dp
    @Composable fun titleSizeSp(): Int = if (isTablet()) 20 else 16
    @Composable fun bodySizeSp(): Int = if (isTablet()) 15 else 13
    @Composable fun listColumns(): Int = if (isTablet()) 2 else 1
    @Composable fun screenPadding(): PaddingValues = PaddingValues(
        horizontal = horizontalPadding(),
        vertical = if (isTablet()) 16.dp else 12.dp
    )
}