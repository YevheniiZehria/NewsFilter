package ro.newsfilter.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatarea datei de publicare a știrilor, în română și în fusul orar al telefonului.
 *
 * `publishedAt` vine ca ISO 8601 UTC: din Firestore (`toISOString()` în jobul `sync/`, ex.
 * „2026-11-27T07:14:00.000Z”) sau din Google News RSS (`<pubDate>` convertit în
 * `GoogleNewsRssSource`, ex. „2026-11-27T07:14:00Z”). Un text care nu poate fi citit → null
 * (UI-ul ascunde eticheta, nu afișează o dată greșită).
 */
object PublishedDate {

    private val romanian: Locale = Locale.forLanguageTag("ro-RO")
    private val time = DateTimeFormatter.ofPattern("HH:mm", romanian)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", romanian)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", romanian)
    private val full = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, 'ora' HH:mm", romanian)

    /** ISO 8601 (cu sau fără fus orar; fără fus = UTC) → momentul exact. */
    fun parse(iso: String): Instant? {
        if (iso.isBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .recoverCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }
            .getOrNull()
    }

    /**
     * Eticheta scurtă de pe carduri, cu ora exactă mereu vizibilă:
     * „azi, 09:14” / „ieri, 21:30” / „27 nov., 09:14” / „3 mar. 2025, 18:05”.
     */
    fun short(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String? {
        val dateTime = parse(iso)?.atZone(zone) ?: return null
        val date = dateTime.toLocalDate()
        val day = when {
            date == today -> "azi"
            date == today.minusDays(1) -> "ieri"
            date.year == today.year -> dateTime.format(dayMonth)
            else -> dateTime.format(dayMonthYear)
        }
        return "$day, ${dateTime.format(time)}"
    }

    /** Eticheta completă din ecranul de detaliu: „Vineri, 27 noiembrie 2026, ora 09:14”. */
    fun long(iso: String, zone: ZoneId = ZoneId.systemDefault()): String? {
        val dateTime: ZonedDateTime = parse(iso)?.atZone(zone) ?: return null
        return dateTime.format(full).replaceFirstChar { it.titlecase(romanian) }
    }
}
