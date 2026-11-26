package ro.newsfilter.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Brush animat pentru efectul shimmer.
 *
 * Decizie tehnică:
 * - Culorile provin din tema curentă (surfaceContainerHighest → surfaceContainerLow),
 *   deci shimmer-ul arată corect atât pe Light, cât și pe Dark (nu mai folosim
 *   `Color.LightGray`, care pe fundal închis părea „pătat").
 * - Gradientul se deplasează orizontal, cu viteză constantă (LinearEasing),
 *   pe toată lățimea ecranului + lățimea benzii de lumină, ca reluarea buclei
 *   să nu se observe.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow

    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val bandWidthPx = screenWidthPx * 0.6f

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue = -bandWidthPx,
        targetValue = screenWidthPx + bandWidthPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translateX - bandWidthPx, 0f),
        end = Offset(translateX, 0f)
    )
}

/**
 * Efect shimmer (loading skeleton) care imită structura feed-ului:
 * un card Hero (imagine 16:9 + titlu) urmat de carduri compacte (text + miniatură).
 */
@Composable
fun LoadingShimmer(
    modifier: Modifier = Modifier,
    compactCount: Int = 3
) {
    val brush = rememberShimmerBrush()

    Column(
        modifier = modifier.semantics { contentDescription = "Se încarcă știrile" },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ShimmerHero(brush)
        Spacer(modifier = Modifier.height(4.dp))
        repeat(compactCount) { ShimmerCompact(brush) }
    }
}

@Composable
private fun ShimmerHero(brush: Brush) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(brush)
        )
        Column(modifier = Modifier.padding(18.dp)) {
            ShimmerLine(brush, fraction = 0.95f, height = 20.dp)
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerLine(brush, fraction = 0.7f, height = 20.dp)
            Spacer(modifier = Modifier.height(14.dp))
            ShimmerBlock(brush, width = 120.dp, height = 12.dp)
        }
    }
}

@Composable
private fun ShimmerCompact(brush: Brush) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBlock(brush, width = 110.dp, height = 12.dp)
            Spacer(modifier = Modifier.height(10.dp))
            ShimmerLine(brush, fraction = 0.95f, height = 16.dp)
            Spacer(modifier = Modifier.height(6.dp))
            ShimmerLine(brush, fraction = 0.6f, height = 16.dp)
            Spacer(modifier = Modifier.height(14.dp))
            ShimmerBlock(brush, width = 48.dp, height = 12.dp)
        }
        Spacer(modifier = Modifier.width(14.dp))
        ShimmerBlock(brush, width = 88.dp, height = 88.dp, shape = RoundedCornerShape(14.dp))
    }
}

@Composable
private fun ShimmerLine(brush: Brush, fraction: Float, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(brush)
    )
}

@Composable
private fun ShimmerBlock(
    brush: Brush,
    width: Dp,
    height: Dp,
    shape: Shape = RoundedCornerShape(4.dp)
) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(brush)
    )
}
