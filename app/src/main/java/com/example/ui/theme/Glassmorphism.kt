package com.example.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ambient background with deep obsidian foundation and soft glowing cyan/violet/blue gradient orbs
 * that creates the frosted-glass backdrop effect.
 */
@Composable
fun MeshBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mesh_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepObsidian)
            .drawBehind {
                // Base subtle dark gradient
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF090E17),
                            Color(0xFF0F172A),
                            Color(0xFF070B12)
                        )
                    )
                )

                // Top right glowing violet orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonPurple.copy(alpha = 0.35f * pulseAlpha),
                            ElectricViolet.copy(alpha = 0.15f * pulseAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.85f, size.height * 0.12f),
                        radius = size.width * 0.65f
                    )
                )

                // Mid-left glowing cyan orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonCyan.copy(alpha = 0.28f * pulseAlpha),
                            GlowBlue.copy(alpha = 0.12f * pulseAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.15f, size.height * 0.45f),
                        radius = size.width * 0.6f
                    )
                )

                // Bottom right deep indigo/cyan light pool
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            GlowBlue.copy(alpha = 0.25f * pulseAlpha),
                            DeepViolet.copy(alpha = 0.40f * pulseAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.75f, size.height * 0.85f),
                        radius = size.width * 0.7f
                    )
                )
            },
        content = content
    )
}

/**
 * Modifier that applies translucent glassmorphic styling:
 * - Frosted semi-transparent background fill
 * - Crisp gradient specular border
 * - Rounded corners
 */
fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(22.dp),
    backgroundColor: Color = CardGlassBg,
    borderColor: Color = CardGlassBorder,
    borderWidth: Dp = 1.dp,
    elevation: Dp = 0.dp
): Modifier = this
    .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
    .clip(shape)
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(
                backgroundColor.copy(alpha = (backgroundColor.alpha * 1.25f).coerceAtMost(0.40f)),
                backgroundColor.copy(alpha = (backgroundColor.alpha * 0.80f).coerceAtLeast(0.08f))
            )
        )
    )
    .border(
        width = borderWidth,
        brush = Brush.linearGradient(
            colors = listOf(
                borderColor.copy(alpha = 0.55f),
                borderColor.copy(alpha = 0.15f),
                CyanAccent.copy(alpha = 0.25f),
                Color.Transparent
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        ),
        shape = shape
    )

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = Color(0x1AFFFFFF),
    borderColor: Color = Color(0x35FFFFFF),
    borderWidth: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = CyanAccent.copy(alpha = 0.3f)),
            onClick = onClick
        )
    } else Modifier

    Box(
        modifier = modifier
            .glassPanel(
                shape = shape,
                backgroundColor = backgroundColor,
                borderColor = borderColor,
                borderWidth = borderWidth
            )
            .then(clickableModifier),
        content = content
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val bgBrush = if (isPrimary) {
        Brush.horizontalGradient(
            colors = if (enabled) listOf(CyanAccent, NeonPurple) else listOf(Color.Gray.copy(alpha = 0.4f), Color.DarkGray.copy(alpha = 0.4f))
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(Color(0x30FFFFFF), Color(0x18FFFFFF))
        )
    }

    val borderBrush = if (isPrimary) {
        Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.6f), CyanAccent.copy(alpha = 0.4f))
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgBrush)
            .border(1.dp, borderBrush, shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = Color.White.copy(alpha = 0.4f)),
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}
