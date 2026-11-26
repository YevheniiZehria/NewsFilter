package ro.newsfilter.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ro.newsfilter.app.domain.model.MarketHistory
import ro.newsfilter.app.domain.model.MarketHistoryEvent
import ro.newsfilter.app.domain.model.MarketPoint
import ro.newsfilter.app.domain.model.MarketRange
import ro.newsfilter.app.domain.model.MarketSentiment
import kotlin.math.abs

internal val ChartHeight = 200.dp

/**
 * Graficul de curs din widget-ul valutar, desenat cu `Canvas` (fără biblioteci externe):
 *  - linie fină netezită (curbe Bézier) + gradient care se estompează spre bază;
 *  - linii de grilă orizontale la valori „rotunde”, cu prețul în dreapta;
 *  - datele pe axa de jos, în funcție de interval;
 *  - glisare orizontală → linie verticală + punct care urmărește linia ([onScrub]);
 *  - puncte colorate pentru știrile UP / DOWN; atingere → [onEventTap].
 * Gestul vertical rămâne al listei, deci feed-ul se derulează normal peste grafic.
 */
@Composable
fun MarketLineChart(
    history: MarketHistory,
    lineColor: Color,
    selectedEvent: MarketHistoryEvent?,
    onScrub: (MarketPoint?) -> Unit,
    onEventTap: (MarketHistoryEvent?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = ChartHeight
) {
    val points = history.points
    val events = history.events
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val crosshairColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerRing = MaterialTheme.colorScheme.surfaceContainerLow
    val upColor = sentimentColor(MarketSentiment.UP)
    val downColor = sentimentColor(MarketSentiment.DOWN)

    // Grila și lățimea coloanei de prețuri depind doar de serie.
    val ticks = remember(points) { niceTicks(points.minOf { it.rate }, points.maxOf { it.rate }) }
    val tickStep = if (ticks.size > 1) ticks[1] - ticks[0] else 1.0
    val tickLabels = remember(ticks) { ticks.map { formatAxisValue(it, tickStep) } }
    val gutterPx = remember(tickLabels, labelStyle) {
        tickLabels.maxOf { textMeasurer.measure(it, labelStyle).size.width } + with(density) { 8.dp.toPx() }
    }
    val axisTimes = remember(points) {
        val t0 = points.first().timestampMillis
        val t1 = points.last().timestampMillis
        (0..3).map { t0 + (t1 - t0) * it / 3 }
    }
    var scrubIndex by remember(points) { mutableStateOf<Int?>(null) }

    fun geometryFor(width: Float, heightPx: Float, d: Density) =
        ChartGeometry(points, ticks.first(), ticks.last(), width, heightPx, gutterPx, d)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = "Graficul ${history.pair}. Glisează orizontal pentru valori exacte."
            }
            .pointerInput(points, gutterPx) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val g = geometryFor(size.width.toFloat(), size.height.toFloat(), this)
                        val index = g.nearestIndex(offset.x)
                        scrubIndex = index
                        onScrub(points[index])
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onDragEnd = { scrubIndex = null; onScrub(null) },
                    onDragCancel = { scrubIndex = null; onScrub(null) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val g = geometryFor(size.width.toFloat(), size.height.toFloat(), this)
                        val index = g.nearestIndex(change.position.x)
                        if (index != scrubIndex) {
                            scrubIndex = index
                            onScrub(points[index])
                        }
                    }
                )
            }
            .pointerInput(points, events, gutterPx) {
                detectTapGestures { offset ->
                    val g = geometryFor(size.width.toFloat(), size.height.toFloat(), this)
                    val hitX = 18.dp.toPx()
                    val hitY = 32.dp.toPx()
                    val hit = events
                        .map { it to g.markerOffset(it.timestampMillis) }
                        .filter { (_, o) -> abs(o.x - offset.x) < hitX && abs(o.y - offset.y) < hitY }
                        .minByOrNull { (_, o) -> abs(o.x - offset.x) }
                        ?.first
                    if (hit != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEventTap(hit)
                }
            }
    ) {
        val g = geometryFor(size.width, size.height, this)

        // Grila orizontală + prețurile în dreapta
        ticks.forEachIndexed { i, tick ->
            val y = g.y(tick)
            drawLine(gridColor, Offset(0f, y), Offset(g.plotRight, y), strokeWidth = 1.dp.toPx())
            val layout = textMeasurer.measure(tickLabels[i], labelStyle)
            drawText(
                layout,
                topLeft = Offset(
                    size.width - layout.size.width,
                    (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                )
            )
        }

        // Datele pe axa de jos
        axisTimes.forEachIndexed { i, t ->
            val layout = textMeasurer.measure(formatAxisTime(t, history.range), labelStyle)
            val x = g.x(t)
            val left = when (i) {
                0 -> 0f
                axisTimes.lastIndex -> g.plotRight - layout.size.width
                else -> x - layout.size.width / 2f
            }
            drawText(layout, topLeft = Offset(left.coerceAtLeast(0f), size.height - layout.size.height))
        }

        // Gradient sub linie + linia
        val linePath = g.smoothPath()
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(g.x(points.last().timestampMillis), g.plotBottom)
            lineTo(g.x(points.first().timestampMillis), g.plotBottom)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0f)),
                startY = g.plotTop,
                endY = g.plotBottom
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Puncte-știre
        events.forEach { event ->
            val center = g.markerOffset(event.timestampMillis)
            val radius = (if (event == selectedEvent) 6 else 4).dp.toPx()
            drawCircle(markerRing, radius + 2.dp.toPx(), center)
            drawCircle(if (event.sentiment == MarketSentiment.UP) upColor else downColor, radius, center)
        }

        // Indicatorul de scrubbing: linie verticală + punct pe linie
        scrubIndex?.let { index ->
            val point = points[index]
            val center = Offset(g.x(point.timestampMillis), g.y(point.rate))
            drawLine(
                color = crosshairColor.copy(alpha = 0.6f),
                start = Offset(center.x, g.plotTop),
                end = Offset(center.x, g.plotBottom),
                strokeWidth = 1.dp.toPx()
            )
            drawCircle(markerRing, 6.dp.toPx(), center)
            drawCircle(lineColor, 4.5.dp.toPx(), center)
        }
    }
}

/** Placeholder discret (shimmer) cât timp se descarcă seria. */
@Composable
internal fun ChartPlaceholder(height: Dp = ChartHeight) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(rememberShimmerBrush())
            .semantics { contentDescription = "Se încarcă graficul" }
    )
}

/** Mesaj în locul graficului (offline, eroare, date insuficiente). */
@Composable
internal fun ChartMessage(message: String, height: Dp = ChartHeight) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

/** Știrea selectată de pe grafic (atingere → articolul). */
@Composable
internal fun ChartEventBanner(
    event: MarketHistoryEvent,
    range: MarketRange,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = sentimentColor(event.sentiment)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .clickable(onClickLabel = "Deschide știrea", onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${event.sentiment.arrow} ${formatPointTime(event.timestampMillis, range)}",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            event.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- geometrie

/**
 * Transformarea (timp, curs) → pixeli, comună desenului și gesturilor.
 * Scala verticală este exact intervalul grilei [lo, hi]; dreapta rămâne liberă pentru prețuri,
 * iar jos pentru axa timpului.
 */
private class ChartGeometry(
    private val points: List<MarketPoint>,
    private val lo: Double,
    private val hi: Double,
    width: Float,
    height: Float,
    gutter: Float,
    density: Density
) {
    private val hPad = with(density) { 4.dp.toPx() }
    val plotTop = with(density) { 8.dp.toPx() }
    val plotBottom = height - with(density) { 22.dp.toPx() }
    val plotRight = width - gutter
    private val plotWidth = plotRight - 2 * hPad

    private val t0 = points.first().timestampMillis
    private val t1 = points.last().timestampMillis

    fun x(t: Long): Float {
        if (t1 == t0) return hPad + plotWidth / 2
        return hPad + (t.coerceIn(t0, t1) - t0).toFloat() / (t1 - t0) * plotWidth
    }

    fun y(rate: Double): Float =
        if (hi == lo) (plotTop + plotBottom) / 2
        else plotBottom - ((rate - lo) / (hi - lo)).toFloat() * (plotBottom - plotTop)

    fun nearestIndex(px: Float): Int =
        points.indices.minByOrNull { abs(x(points[it].timestampMillis) - px) } ?: 0

    /** Poziția unui punct-știre: pe linie, la momentul publicării (interpolare liniară). */
    fun markerOffset(t: Long): Offset {
        val clamped = t.coerceIn(t0, t1)
        val after = points.indexOfFirst { it.timestampMillis >= clamped }.let { if (it < 0) points.lastIndex else it }
        val before = (after - 1).coerceAtLeast(0)
        val a = points[before]
        val b = points[after]
        val rate = if (b.timestampMillis == a.timestampMillis) b.rate
        else a.rate + (b.rate - a.rate) * (clamped - a.timestampMillis) / (b.timestampMillis - a.timestampMillis)
        return Offset(x(clamped), y(rate))
    }

    /** Linie netezită: curbe Bézier cu tangente orizontale (fără „depășiri” peste grilă). */
    fun smoothPath(): Path = Path().apply {
        val first = points.first()
        moveTo(x(first.timestampMillis), y(first.rate))
        for (i in 1 until points.size) {
            val px = x(points[i - 1].timestampMillis)
            val py = y(points[i - 1].rate)
            val cx = x(points[i].timestampMillis)
            val cy = y(points[i].rate)
            val mid = (px + cx) / 2
            cubicTo(mid, py, mid, cy, cx, cy)
        }
    }
}
