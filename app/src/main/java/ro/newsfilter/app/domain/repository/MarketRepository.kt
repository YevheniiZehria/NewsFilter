package ro.newsfilter.app.domain.repository

import kotlinx.coroutines.flow.Flow
import ro.newsfilter.app.domain.model.MarketHistory
import ro.newsfilter.app.domain.model.MarketPulse
import ro.newsfilter.app.domain.model.MarketRange

/**
 * Market Pulse: cursuri valutare live, istoricul lor și știrile care le influențează.
 * Cursurile și istoricul se țin doar în memorie (nu sunt salvate în Room); știrile cu
 * impact valutar vin din cache-ul local de articole.
 */
interface MarketRepository {

    /**
     * Cursurile curente, în timp real (listener pe `market/pulse`: o citire doar când jobul de
     * sincronizare le schimbă) + cea mai recentă știre cu impact clar din ultimele 48 h.
     * Flow-ul aruncă excepții Firestore (vezi `Throwable.toUserMessage()`).
     */
    fun observeMarketPulse(): Flow<MarketPulse>

    /**
     * Istoricul unei perechi pe interval. Documentele descărcate în ultimele 30 de minute se
     * servesc din memorie. Aruncă excepții; ultimul rezultat rămâne disponibil prin [cachedMarketHistory].
     */
    suspend fun fetchMarketHistory(pair: String, range: MarketRange): MarketHistory

    /** Ultimul istoric calculat pentru [pair] / [range] în această sesiune, dacă există. */
    fun cachedMarketHistory(pair: String, range: MarketRange): MarketHistory?
}
