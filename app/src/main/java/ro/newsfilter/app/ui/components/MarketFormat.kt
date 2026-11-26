package ro.newsfilter.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import ro.newsfilter.app.domain.model.Currency
import ro.newsfilter.app.domain.model.MarketRange
import ro.newsfilter.app.domain.model.MarketSentiment
import ro.newsfilter.app.ui.theme.TrendDownDark
import ro.newsfilter.app.ui.theme.TrendDownLight
import ro.newsfilter.app.ui.theme.TrendUpDark
import ro.newsfilter.app.ui.theme.TrendUpLight
import java.math.BigDecimal
import java.math.MathContext
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/*
 * Utilitare comune pentru widget-ul valutar, graficul de curs și pastilele de piață
 * de pe cardurile de știri: culori de tendință și formatare în stil românesc.
 */

internal val RomanianLocale: Locale = Locale.forLanguageTag("ro-RO")

// ---------------------------------------------------------------- culori

/** Verde (true) / roșu (false) / neutru (null), adaptat temei luminoase sau întunecate. */
@Composable
fun trendColor(up: Boolean?): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (up) {
        true -> if (dark) TrendUpDark else TrendUpLight
        false -> if (dark) TrendDownDark else TrendDownLight
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Culoarea pentru direcția estimată de AI. */
@Composable
fun sentimentColor(sentiment: MarketSentiment?): Color = trendColor(
    when (sentiment) {
        MarketSentiment.UP -> true
        MarketSentiment.DOWN -> false
        else -> null
    }
)

// ---------------------------------------------------------------- numere

private fun numberFormat(decimals: Int, grouping: Boolean = true): NumberFormat =
    NumberFormat.getNumberInstance(RomanianLocale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
        isGroupingUsed = grouping
    }

/**
 * Cursul mare din widget: 2 zecimale peste 1 („4,87”, „60.611,00”), altfel 3 cifre
 * semnificative („0,205”, „0,0000165”), ca să nu ajungă la „0,00”.
 */
internal fun formatWidgetRate(value: Double): String {
    if (abs(value) >= 1) return numberFormat(2).format(value)
    val rounded = BigDecimal(value).round(MathContext(3))
    return numberFormat(max(rounded.scale(), 2)).format(rounded)
}

/** Variație absolută a cursului, cu precizia potrivită mărimii lui. */
internal fun formatRateDelta(delta: Double, reference: Double): String =
    if (abs(reference) >= 1) numberFormat(if (abs(reference) >= 1000) 2 else 4).format(abs(delta))
    else formatWidgetRate(abs(delta))

/** Variație procentuală în valoare absolută („0,35%”); semnul îl dă săgeata. */
internal fun formatMarketPercent(value: Double): String = numberFormat(2).format(abs(value)) + "%"

/**
 * Eticheta unei linii de grilă: exact câte zecimale are pasul grilei
 * (0,05 → „4,85”, 0,025 → „4,825”, 2.000 → „60.000”).
 */
internal fun formatAxisValue(value: Double, step: Double): String {
    var decimals = 0
    while (decimals < 8) {
        val scaled = step * 10.0.pow(decimals)
        if (abs(scaled - Math.round(scaled)) < 1e-6) break
        decimals++
    }
    return numberFormat(decimals).format(value)
}

/**
 * Valoarea din convertor, fără separator de mii (ca să poată fi editată): BTC până la
 * 8 zecimale, celelalte monede 2 zecimale.
 */
internal fun formatAmount(value: Double, currency: Currency): String =
    if (currency == Currency.BTC) {
        NumberFormat.getNumberInstance(RomanianLocale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 8
            isGroupingUsed = false
        }.format(value)
    } else {
        numberFormat(2, grouping = false).format(value)
    }

/** Citește o sumă scrisă cu virgulă sau punct zecimal („1,5” / „1.5”); null dacă e invalidă. */
internal fun parseAmount(text: String): Double? =
    text.trim().replace(" ", "").replace(',', '.')
        .takeIf { it.isNotEmpty() && it.count { c -> c == '.' } <= 1 }
        ?.toDoubleOrNull()
        ?.takeIf { it >= 0 && it.isFinite() }

/** Păstrează doar cifre și un singur separator zecimal, max. 16 caractere. */
internal fun sanitizeAmountInput(text: String): String {
    val builder = StringBuilder()
    var hasSeparator = false
    for (c in text) {
        when {
            c.isDigit() -> builder.append(c)
            (c == ',' || c == '.') && !hasSeparator -> {
                hasSeparator = true
                builder.append(',')
            }
        }
        if (builder.length >= 16) break
    }
    return builder.toString()
}

/** Linii de grilă „rotunde” (pași de 1 / 2 / 2,5 / 5 × 10ⁿ) care acoperă [min, max]. */
internal fun niceTicks(min: Double, max: Double, targetCount: Int = 5): List<Double> {
    val span = (max - min).takeIf { it > 0 } ?: (abs(max) * 0.01).coerceAtLeast(1e-9)
    val rawStep = span / (targetCount - 1)
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val step = when (rawStep / magnitude) {
        in 0.0..1.0 -> 1.0
        in 1.0..2.0 -> 2.0
        in 2.0..2.5 -> 2.5
        in 2.5..5.0 -> 5.0
        else -> 10.0
    } * magnitude
    val start = floor(min / step) * step
    val end = ceil(max / step) * step
    val ticks = mutableListOf<Double>()
    var value = start
    while (value <= end + step / 2) {
        ticks += value
        value += step
    }
    if (ticks.size < 2) ticks += start + step
    return ticks
}

// ---------------------------------------------------------------- timp

/** Ora cursului live, ca în sursele financiare: „26 nov., 16:05 UTC”. */
internal fun formatRateTimestamp(iso: String): String? = runCatching {
    DateTimeFormatter.ofPattern("d MMM, HH:mm 'UTC'", RomanianLocale)
        .format(Instant.parse(iso).atOffset(ZoneOffset.UTC))
}.getOrNull()

/** Momentul unui punct de pe grafic: cu oră pe 1 Z / 5 Z / 1 L, doar data pe 1 A / Max. */
internal fun formatPointTime(millis: Long, range: MarketRange): String {
    val pattern = when (range) {
        MarketRange.D1, MarketRange.D5, MarketRange.M1 -> "d MMM yyyy, HH:mm"
        MarketRange.Y1, MarketRange.MAX -> "d MMM yyyy"
    }
    return DateTimeFormatter.ofPattern(pattern, RomanianLocale)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

/** Eticheta axei timpului, în funcție de interval („14:30”, „lun. 24”, „5 nov.”, „nov. 2024”). */
internal fun formatAxisTime(millis: Long, range: MarketRange): String {
    val pattern = when (range) {
        MarketRange.D1 -> "HH:mm"
        MarketRange.D5 -> "EEE d"
        MarketRange.M1, MarketRange.Y1 -> "d MMM"
        MarketRange.MAX -> "MMM yyyy"
    }
    return DateTimeFormatter.ofPattern(pattern, RomanianLocale)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

/** „în ultima zi”, „în ultimele 5 zile”… (pentru variația pe interval). */
internal fun rangeDescription(range: MarketRange): String = when (range) {
    MarketRange.D1 -> "în ultima zi"
    MarketRange.D5 -> "în ultimele 5 zile"
    MarketRange.M1 -> "în ultima lună"
    MarketRange.Y1 -> "în ultimul an"
    MarketRange.MAX -> "în toată perioada"
}
