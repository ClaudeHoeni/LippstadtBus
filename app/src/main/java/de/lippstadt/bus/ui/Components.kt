package de.lippstadt.bus.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardShape = RoundedCornerShape(20.dp)

@Composable
fun DarkCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.shadow(10.dp, CardShape, ambientColor = Color.Black, spotColor = Color.Black),
        shape = CardShape,
        color = AppColors.Card,
        border = BorderStroke(1.dp, AppColors.Outline.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** Karte mit großem orangefarbenen Kreis-Button, der rechts über den Rand ragt (wie in der Vorlage). */
@Composable
fun ActionCard(
    onCircleClick: () -> Unit,
    circleContentDescription: String,
    modifier: Modifier = Modifier,
    circleColor: Color = AppColors.Orange,
    circleEnabled: Boolean = true,
    circleContent: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth()) {
        DarkCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 26.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 50.dp),
            content = content
        )
        OrangeCircle(
            onClick = onCircleClick,
            contentDescription = circleContentDescription,
            color = circleColor,
            enabled = circleEnabled,
            modifier = Modifier.align(Alignment.CenterEnd),
            content = circleContent
        )
    }
}

@Composable
fun OrangeCircle(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    color: Color = AppColors.Orange,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(14.dp, CircleShape, ambientColor = color, spotColor = color)
            .clip(CircleShape)
            .background(if (enabled) color else color.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    color: Color = AppColors.Orange,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (filled) Modifier.background(if (enabled) color else color.copy(alpha = 0.4f))
                else Modifier.border(1.dp, color.copy(alpha = if (enabled) 0.8f else 0.3f), shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (filled) AppColors.Background else color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun SectionLabel(text: String, color: Color = AppColors.Orange, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 6.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextMuted
        )
    }
}

@Composable
fun GlyphText(glyph: String, color: Color = AppColors.Background, size: Int = 26) {
    Text(text = glyph, fontSize = size.sp, color = color, fontWeight = FontWeight.Bold)
}

/** Kompass-Pfeil als Vektor gezeichnet, dreht sich um [angle] Grad (0 = oben). */
@Composable
fun CompassArrow(angle: Float, modifier: Modifier = Modifier, color: Color = AppColors.Background) {
    Canvas(modifier = modifier.rotate(angle)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.86f, h * 0.88f)
            lineTo(w * 0.5f, h * 0.68f)
            lineTo(w * 0.14f, h * 0.88f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = AppColors.Orange
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
    }
}

/** Auswählbare Listenzeile (Alternativen, weitere Ziele). */
@Composable
fun SelectableRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    trailing: String?,
    onClick: () -> Unit,
    accent: Color = AppColors.Orange
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppColors.CardRaised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Dot(if (selected) accent else AppColors.Outline, 10.dp)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = AppColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Text(trailing, color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
