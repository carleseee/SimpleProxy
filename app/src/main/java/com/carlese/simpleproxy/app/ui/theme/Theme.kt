package com.carlese.simpleproxy.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

enum class AppAccentColor(
    val swatch: Color,
    val checkOnSwatch: Color,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val containerLight: Color,
    val onContainerLight: Color,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val containerDark: Color,
    val onContainerDark: Color
) {
    TEAL(Color(0xFF0D9488), Color.White, Color(0xFF0D9488), Color.White, Color(0xFFCCFBF1), Color(0xFF042F2E), Color(0xFF5EEAD4), Color(0xFF042F2E), Color(0xFF115E59), Color(0xFFCCFBF1)),
    BLUE(Color(0xFF0EA5E9), Color.White, Color(0xFF0284C7), Color.White, Color(0xFFCBE5FF), Color(0xFF091E32), Color(0xFF38BDF8), Color(0xFF082F49), Color(0xFF075985), Color(0xFFE0F2FE)),
    YELLOW(Color(0xFFFACC15), Color(0xFF0F172A), Color(0xFFEAB308), Color(0xFF0F172A), Color(0xFFFEF9C3), Color(0xFF713F12), Color(0xFFFDE047), Color(0xFF0F172A), Color(0xFF854D0E), Color(0xFFFEF9C3)),
    ORANGE(Color(0xFFEA580C), Color.White, Color(0xFFEA580C), Color.White, Color(0xFFFFEDD5), Color(0xFF7C2D12), Color(0xFFFDBA74), Color(0xFF431407), Color(0xFF9A3412), Color(0xFFFFEDD5)),
    RED(Color(0xFFDC2626), Color.White, Color(0xFFDC2626), Color.White, Color(0xFFFEE2E2), Color(0xFF7F1D1D), Color(0xFFFCA5A5), Color(0xFF450A0A), Color(0xFF991B1B), Color(0xFFFEE2E2)),
    PURPLE(Color(0xFF7C3AED), Color.White, Color(0xFF7C3AED), Color.White, Color(0xFFF3E8FF), Color(0xFF4C1D95), Color(0xFFD8B4FE), Color(0xFF2E1065), Color(0xFF6B21A8), Color(0xFFF3E8FF)),
    BLACK(Color(0xFF18181B), Color.White, Color(0xFF18181B), Color.White, Color(0xFFE4E4E7), Color(0xFF18181B), Color(0xFFF4F4F5), Color(0xFF18181B), Color(0xFF27272A), Color(0xFFF4F4F5))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SimpleProxyTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    accentColor: AppAccentColor = AppAccentColor.BLUE,
    amoledPureBlack: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        useDark -> darkColorScheme(
            primary = accentColor.primaryDark,
            onPrimary = accentColor.onPrimaryDark,
            primaryContainer = accentColor.containerDark,
            onPrimaryContainer = accentColor.onContainerDark,
            background = Color(0xFF0B1120),
            surface = Color(0xFF111827),
            surfaceContainerLowest = Color(0xFF0B1120),
            surfaceContainerLow = Color(0xFF111827),
            surfaceContainer = Color(0xFF1E293B),
            surfaceContainerHigh = Color(0xFF273449)
        )
        else -> lightColorScheme(
            primary = accentColor.primaryLight,
            onPrimary = accentColor.onPrimaryLight,
            primaryContainer = accentColor.containerLight,
            onPrimaryContainer = accentColor.onContainerLight,
            background = Color(0xFFF8FAFC),
            surface = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainer = Color(0xFFF1F5F9),
            surfaceContainerHigh = Color(0xFFE2E8F0)
        )
    }

    val colorScheme = remember(baseScheme, useDark, amoledPureBlack) {
        if (useDark && amoledPureBlack) {
            baseScheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF0A0A0A),
                surfaceContainer = Color(0xFF111111),
                surfaceContainerHigh = Color(0xFF181818)
            )
        } else baseScheme
    }
    val motionScheme = remember { MotionScheme.expressive() }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = motionScheme,
        content = content
    )
}

private data class TvFocusRingElement(
    val shape: Shape,
    val strokeWidth: Dp,
    val outerGlow: Color,
    val crispRing: Color
) : ModifierNodeElement<TvFocusRingNode>() {
    override fun create() = TvFocusRingNode(shape, strokeWidth, outerGlow, crispRing)

    override fun update(node: TvFocusRingNode) {
        val changed = node.shape != shape ||
            node.strokeWidth != strokeWidth ||
            node.outerGlow != outerGlow ||
            node.crispRing != crispRing
        node.shape = shape
        node.strokeWidth = strokeWidth
        node.outerGlow = outerGlow
        node.crispRing = crispRing
        if (changed && node.focused) {
            node.invalidateDraw()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocusRing"
        properties["shape"] = shape
        properties["strokeWidth"] = strokeWidth
    }
}

private class TvFocusRingNode(
    var shape: Shape,
    var strokeWidth: Dp,
    var outerGlow: Color,
    var crispRing: Color
) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    var focused: Boolean = false
        private set

    override fun onFocusEvent(focusState: FocusState) {
        val next = focusState.hasFocus
        if (focused != next) {
            focused = next
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                color = outerGlow,
                style = Stroke(width = (strokeWidth * 2f).toPx())
            )
            drawOutline(
                outline = outline,
                color = crispRing,
                style = Stroke(width = strokeWidth.toPx())
            )
        }
    }
}

@Composable
fun Modifier.tvFocusRing(
    shape: Shape = CircleShape,
    strokeWidth: Dp = 2.5.dp
): Modifier {
    val colorScheme = MaterialTheme.colorScheme
    return this then TvFocusRingElement(
        shape = shape,
        strokeWidth = strokeWidth,
        outerGlow = colorScheme.primary.copy(alpha = 0.55f),
        crispRing = colorScheme.onSurface
    )
}
