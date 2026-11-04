package dev.lelonio.square.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.Ink

/** The app name, shown as plain text: "Wave by Robin". */
@Composable
fun SquareWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = Ink,
) {
    Text(
        text = "Wave by Robin",
        color = color,
        fontSize = (height.value * 0.85f).sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** The launcher art beside the name. */
@Composable
fun AppLockup(
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    nameHeight: Dp = 14.dp,
    plate: dev.lelonio.square.ui.glass.backdrop.Backdrop? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (plate == null) {
            AppGlyph(iconSize)
        } else {
            AppPlate(iconSize, plate)
        }
        Spacer(Modifier.width(iconSize * 0.32f))
        SquareWordmark(nameHeight)
    }
}

/** The mark on a round pane of the app's own glass. */
@Composable
fun AppPlate(
    size: Dp,
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
    modifier: Modifier = Modifier,
) {
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    dev.lelonio.square.ui.glass.LiquidButton(
        onClick = {},
        backdrop = backdrop,
        isInteractive = false,
        flat = true,
        modifier = modifier
            .size(size)
            .border(
                0.8.dp,
                androidx.compose.ui.graphics.Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.62f),
                    0.5f to Color.White.copy(alpha = 0.20f),
                    1f to Color.White.copy(alpha = 0.06f),
                ),
                androidx.compose.foundation.shape.CircleShape,
            ),
        contentHeight = size,
        contentPadding = 0.dp,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        0f to accent.copy(alpha = 0.62f),
                        0.5f to accent.copy(alpha = 0.30f),
                        1f to accent.copy(alpha = 0.08f),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(size.toPx(), size.toPx()),
                    ),
                )
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(
                            this.size.width * 0.30f,
                            this.size.height * 0.20f,
                        ),
                        radius = this.size.width * 0.62f,
                    ),
                )
            }
            AppGlyph(size * 0.58f)
        }
    }
}

/** The app's mark with no plate under it. */
@Composable
fun AppGlyph(size: Dp, modifier: Modifier = Modifier, tint: Color = Ink) {
    Icon(
        painter = painterResource(R.drawable.ic_app_mark),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}
