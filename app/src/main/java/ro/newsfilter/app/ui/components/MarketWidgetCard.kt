package ro.newsfilter.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import ro.newsfilter.app.domain.model.MarketEvent
import ro.newsfilter.app.domain.model.MarketHistoryEvent
import ro.newsfilter.app.domain.model.MarketPairs
import ro.newsfilter.app.domain.model.MarketPoint
import ro.newsfilter.app.domain.model.MarketPulse
import ro.newsfilter.app.domain.model.MarketRange
import ro.newsfilter.app.domain.model.MarketSentiment
import ro.newsfilter.app.ui.screens.feed.MarketWidgetState
import ro.newsfilter.app.ui.theme.ChartDown
import ro.newsfilter.app.ui.theme.ChartUp

private val WidgetShape = RoundedCornerShape(20.dp)

/**
 * Widget-ul valutar din capul Feed-ului, în stilul convertoarelor din căutările financiare:
 *  - chip-uri USD/RON · EUR/RON · BTC/USD (+ restrângere);
 *  - „1 Dolar SUA înseamnă” / cursul mare / „Leu românesc” / variația pe interval /
 *    ora · sursa · declinarea răspunderii;
 *  - intervalele 1 Z · 5 Z · 1 L · 1 A · Max și graficul [MarketLineChart]: glisarea pe grafic
 *    actualizează în timp real cursul mare, data și convertorul;
 *  - convertorul [CurrencyConverter] cu butonul ⇅;
 *  - bannerul cu cea mai recentă știre care mișcă piața.
 *
 * Aranjare adaptivă: pe telefon totul este pe o coloană; de la 600dp lățime, antetul și
 * convertorul stau în stânga, iar intervalele și graficul în dreapta.
 */
@Composable
fun MarketWidgetCard(
    pulse: MarketPulse,
    isPulseStale: Boolean,
    widget: MarketWidgetState,
    onPairSelect: (pair: String, inverted: Boolean) -> Unit,
    onSwap: () -> Unit,
    onRangeSelect: (MarketRange) -> Unit,
    onEventClick: (MarketEvent) -> Unit,
    onChartEventClick: (MarketHistoryEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }
    var scrubPoint by remember(widget.pair, widget.inverted, widget.range) { mutableStateOf<MarketPoint?>(null) }
    var selectedEvent by remember(widget.pair, widget.inverted, widget.range) {
        mutableStateOf<MarketHistoryEvent?>(null)
    }

    val history = remember(widget.history, widget.inverted) { widget.displayHistory }
    val points = history?.points.orEmpty()
    val liveRate = pulse.rates.firstOrNull { it.pair == widget.pair }
    val liveValue = liveRate?.rate?.let { if (widget.inverted) 1.0 / it else it }
    val shownValue = scrubPoint?.rate ?: liveValue ?: points.lastOrNull()?.rate
    val periodUp = points.size >= 2 && points.last().rate >= points.first().rate
    val lineColor = if (points.size < 2 || periodUp) ChartUp else ChartDown

    val header: @Composable () -> Unit = {
        RateHeader(
            widget = widget,
            shownValue = shownValue,
            isScrubbing = scrubPoint != null,
            firstValue = points.firstOrNull()?.rate,
            timestamp = scrubPoint?.let { formatPointTime(it.timestampMillis, widget.range) }
                ?: liveRate?.updatedAt?.let(::formatRateTimestamp),
            source = liveRate?.source.orEmpty(),
            isStale = isPulseStale || widget.isStale,
            onDisclaimerClick = { showDisclaimer = true }
        )
    }
    val rangePills: @Composable () -> Unit = {
        RangePills(selected = widget.range, onSelect = onRangeSelect)
    }
    val chart: @Composable () -> Unit = {
        ChartArea(
            widget = widget,
            history = history,
            lineColor = lineColor,
            selectedEvent = selectedEvent,
            onScrub = { scrubPoint = it },
            onEventTap = { selectedEvent = it },
            onChartEventClick = onChartEventClick
        )
    }
    val converter: @Composable () -> Unit = {
        CurrencyConverter(
            from = widget.from,
            to = widget.to,
            rate = shownValue,
            onPairChange = onPairSelect,
            onSwap = onSwap
        )
    }
    val banner: @Composable () -> Unit = {
        pulse.latestEvent?.let { event ->
            EventBanner(event = event, onClick = { onEventClick(event) }, modifier = Modifier.padding(top = 12.dp))
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = WidgetShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(16.dp)
        ) {
            PairChips(
                selected = widget.pair,
                expanded = expanded,
                onSelect = { onPairSelect(it, false) },
                onToggleExpanded = { expanded = !expanded }
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (!expanded) {
                header()
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth >= 600.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(modifier = Modifier.weight(0.42f)) {
                                header()
                                Spacer(modifier = Modifier.height(20.dp))
                                converter()
                                banner()
                            }
                            Column(modifier = Modifier.weight(0.58f)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    rangePills()
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                chart()
                            }
                        }
                    } else {
                        Column {
                            header()
                            Spacer(modifier = Modifier.height(12.dp))
                            rangePills()
                            Spacer(modifier = Modifier.height(8.dp))
                            chart()
                            Spacer(modifier = Modifier.height(16.dp))
                            converter()
                            banner()
                        }
                    }
                }
            }
        }
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text("Am înțeles") } },
            title = { Text("Declinarea răspunderii") },
            text = {
                Text(
                    "Cursurile au caracter informativ. Cursurile fiat provin de la Banca Centrală Europeană " +
                        "(prin Frankfurter) și se actualizează o dată pe zi lucrătoare, iar Bitcoin de la CoinGecko. " +
                        "Valorile pot diferi de cursul BNR sau de cel practicat de bănci și nu reprezintă " +
                        "consultanță financiară. Impactul știrilor este estimat automat de AI."
                )
            }
        )
    }
}

/** Chip-urile de pereche + săgeata de restrângere. */
@Composable
private fun PairChips(
    selected: String,
    expanded: Boolean,
    onSelect: (String) -> Unit,
    onToggleExpanded: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MarketPairs.all.sortedBy { if (it == MarketPairs.DEFAULT) 0 else 1 }.forEach { pair ->
                FilterChip(
                    selected = pair == selected,
                    onClick = { onSelect(pair) },
                    label = { Text(pair, style = MaterialTheme.typography.labelMedium) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
        IconButton(onClick = onToggleExpanded) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Restrânge widget-ul valutar" else "Extinde widget-ul valutar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

/**
 * Antetul: „1 Dolar SUA înseamnă” / cursul mare / „Leu românesc” / variația pe interval /
 * ora · sursa · declinarea răspunderii. La glisarea pe grafic arată punctul de sub deget.
 */
@Composable
private fun RateHeader(
    widget: MarketWidgetState,
    shownValue: Double?,
    isScrubbing: Boolean,
    firstValue: Double?,
    timestamp: String?,
    source: String,
    isStale: Boolean,
    onDisclaimerClick: () -> Unit
) {
    Column {
        Text(
            text = "1 ${widget.from.nameRo} înseamnă",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedContent(
                targetState = shownValue,
                transitionSpec = {
                    val from = initialState
                    val to = targetState
                    if (isScrubbing || from == null || to == null) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val up = to > from
                        (slideInVertically { h -> if (up) h else -h } + fadeIn()) togetherWith
                            (slideOutVertically { h -> if (up) -h else h } + fadeOut()) using
                            SizeTransform(clip = false)
                    }
                },
                label = "widgetRate"
            ) { value ->
                Text(
                    text = value?.let(::formatWidgetRate) ?: "—",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = widget.to.nameRo,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (shownValue != null && firstValue != null && firstValue > 0) {
            val delta = shownValue - firstValue
            val percent = delta / firstValue * 100
            val color = trendColor(if (delta == 0.0) null else delta > 0)
            val arrow = when {
                delta > 0 -> "↑"
                delta < 0 -> "↓"
                else -> "→"
            }
            Text(
                text = "$arrow ${formatRateDelta(delta, shownValue)} (${formatMarketPercent(percent)}) " +
                    rangeDescription(widget.range),
                style = MaterialTheme.typography.labelLarge,
                color = color
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        val linkColor = MaterialTheme.colorScheme.primary
        val meta = buildAnnotatedString {
            timestamp?.let { append(it); append(" · ") }
            if (source.isNotBlank() && !isScrubbing) append("Sursă: $source · ")
            if (isStale) append("neactualizat · ")
            withLink(
                LinkAnnotation.Clickable(
                    tag = "disclaimer",
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onDisclaimerClick() }
                )
            ) { append("Declinarea răspunderii") }
        }
        Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Intervalele 1 Z · 5 Z · 1 L · 1 A · Max (pastile mici, cea selectată cu fundal tonal). */
@Composable
private fun RangePills(selected: MarketRange, onSelect: (MarketRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MarketRange.entries.forEach { range ->
            val isSelected = range == selected
            Text(
                text = range.label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(role = Role.Tab, onClickLabel = "Interval ${range.label}") { onSelect(range) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** Graficul sau starea lui (încărcare / offline / date insuficiente) + știrea selectată. */
@Composable
private fun ChartArea(
    widget: MarketWidgetState,
    history: ro.newsfilter.app.domain.model.MarketHistory?,
    lineColor: Color,
    selectedEvent: MarketHistoryEvent?,
    onScrub: (MarketPoint?) -> Unit,
    onEventTap: (MarketHistoryEvent?) -> Unit,
    onChartEventClick: (MarketHistoryEvent) -> Unit
) {
    val points = history?.points.orEmpty()
    Column {
        when {
            history == null && widget.isLoading -> ChartPlaceholder()
            history == null -> ChartMessage(widget.error ?: "Graficul nu este disponibil momentan.")
            points.size < 2 -> ChartMessage("Nu sunt încă suficiente date pentru acest interval.")
            else -> MarketLineChart(
                history = history,
                lineColor = lineColor,
                selectedEvent = selectedEvent,
                onScrub = onScrub,
                onEventTap = onEventTap
            )
        }
        val events = history?.events.orEmpty()
        when {
            selectedEvent != null -> ChartEventBanner(
                event = selectedEvent,
                range = widget.range,
                onClick = { onChartEventClick(selectedEvent) },
                modifier = Modifier.padding(top = 8.dp)
            )
            events.isNotEmpty() && points.size >= 2 -> Text(
                text = if (events.size == 1) "● 1 știre a mișcat cursul — atinge punctul de pe grafic"
                else "● ${events.size} știri au mișcat cursul — atinge un punct de pe grafic",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** Bannerul „Știrea care mișcă piața” (cea mai recentă știre UP / DOWN, oricare pereche). */
@Composable
private fun EventBanner(event: MarketEvent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = sentimentColor(event.sentiment)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .clickable(onClickLabel = "Deschide știrea", onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${if (event.sentiment == MarketSentiment.DOWN) "📉" else "📈"} ${event.pair} ${event.sentiment.arrow}" +
                    "  ·  Știrea care mișcă piața",
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            event.note?.let { note ->
                Text(
                    text = note,
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
