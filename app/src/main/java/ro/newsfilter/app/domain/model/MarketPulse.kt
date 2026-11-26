package ro.newsfilter.app.domain.model

/**
 * Direcția estimată de AI pentru o pereche valutară:
 * [UP] = prima monedă se apreciază față de a doua, [DOWN] = se depreciază, [NEUTRAL] = impact neclar.
 */
enum class MarketSentiment(val arrow: String) {
    UP("↑"), DOWN("↓"), NEUTRAL("→");

    /** Direcția văzută din perechea inversă (ex. USD/RON ↑ = RON/USD ↓). */
    fun inverted(): MarketSentiment = when (this) {
        UP -> DOWN
        DOWN -> UP
        NEUTRAL -> NEUTRAL
    }

    companion object {
        fun fromRaw(value: String?): MarketSentiment? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Monedele din widget-ul valutar, cu denumirea în română. */
enum class Currency(val code: String, val nameRo: String) {
    USD("USD", "Dolar SUA"),
    EUR("EUR", "Euro"),
    RON("RON", "Leu românesc"),
    BTC("BTC", "Bitcoin");

    companion object {
        fun fromCode(code: String): Currency? = entries.firstOrNull { it.code == code }
    }
}

/** Perechile urmărite (identice cu cele din jobul `sync/` / documentul `market/pulse`). */
object MarketPairs {
    val all = listOf("EUR/RON", "USD/RON", "BTC/USD")

    /** Perechea implicită a widget-ului valutar. */
    const val DEFAULT = "USD/RON"

    fun normalize(value: String?): String? =
        value?.uppercase()?.replace(" ", "")?.takeIf { it in all }

    /** „USD/RON” → (USD, RON). */
    fun split(pair: String): Pair<Currency, Currency> {
        val (base, quote) = pair.split("/")
        return Currency.fromCode(base)!! to Currency.fromCode(quote)!!
    }

    /**
     * Perechea stocată pentru conversia [from] → [to]: (pereche, inversată?), sau null dacă
     * nu avem curs pentru combinația respectivă (ex. EUR → BTC).
     */
    fun pairFor(from: Currency, to: Currency): Pair<String, Boolean>? {
        all.forEach { pair ->
            val (base, quote) = split(pair)
            if (base == from && quote == to) return pair to false
            if (base == to && quote == from) return pair to true
        }
        return null
    }

    /** Monedele în care se poate converti [from] (pentru lista derulantă). */
    fun counterparts(from: Currency): List<Currency> =
        Currency.entries.filter { it != from && pairFor(from, it) != null }
}

/**
 * Un curs valutar live.
 * @property changePercent variația față de ziua BCE anterioară (fiat) / ultimele 24h (crypto)
 * @property source sursa cursului (ex. „BCE (Frankfurter)”, „CoinGecko”)
 */
data class MarketRate(
    val pair: String,
    val rate: Double,
    val changePercent: Double?,
    val updatedAt: String,
    val source: String = ""
)

/** Cea mai recentă știre care, după AI, mișcă o pereche valutară. */
data class MarketEvent(
    val articleId: String,
    val title: String,
    val articleUrl: String,
    val pair: String,
    val sentiment: MarketSentiment,
    val note: String?,
    val publishedAt: String
)

/** Intervalele graficului (cheia de cache [apiValue]) și eticheta din UI. */
enum class MarketRange(val apiValue: String, val label: String) {
    D1("1d", "1 Z"), D5("5d", "5 Z"), M1("1m", "1 L"), Y1("1y", "1 A"), MAX("max", "Max")
}

/** Un punct din seria de curs (agregat pe server pe găleți, în funcție de interval). */
data class MarketPoint(val timestampMillis: Long, val rate: Double)

/** O știre UP / DOWN pentru pereche, afișată ca punct pe grafic. */
data class MarketHistoryEvent(
    val articleId: String,
    val title: String,
    val articleUrl: String,
    val sentiment: MarketSentiment,
    val note: String?,
    val timestampMillis: Long
)

/** Istoricul unei perechi pe interval: seria cronologică + știrile cu impact din interval. */
data class MarketHistory(
    val pair: String,
    val range: MarketRange,
    val points: List<MarketPoint>,
    val events: List<MarketHistoryEvent>
) {
    /** Seria pentru direcția inversă (ex. RON → USD): cursul devine 1/x, iar direcția știrilor se inversează. */
    fun inverted(): MarketHistory = copy(
        points = points.map { it.copy(rate = 1.0 / it.rate) },
        events = events.map { it.copy(sentiment = it.sentiment.inverted()) }
    )
}

/**
 * Sentimentul comunității pentru o pereche: câte știri validate de cititori (upvotes > downvotes)
 * anunță aprecierea ([bullish]) sau deprecierea ([bearish]) primei monede.
 */
data class CommunitySentiment(val bullish: Int, val bearish: Int) {
    val total: Int get() = bullish + bearish

    /** Văzut din perechea inversă (ex. USD/RON bullish = RON/USD bearish). */
    fun inverted(): CommunitySentiment = CommunitySentiment(bullish = bearish, bearish = bullish)
}

/**
 * Cursurile curente (`market/pulse`) + cea mai recentă știre care le influențează
 * + sentimentul comunității pe pereche ([communitySentiment], calculat din Room).
 */
data class MarketPulse(
    val rates: List<MarketRate>,
    val latestEvent: MarketEvent?,
    val communitySentiment: Map<String, CommunitySentiment> = emptyMap()
)
