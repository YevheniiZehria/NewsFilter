package ro.newsfilter.app.domain.model

/**
 * Categoriile de știri disponibile în aplicație.
 *
 * Decizie tehnică: Folosim un enum cu label-ul afișat în limba română
 * și un slug (identic cu valoarea din API/baza de date) pentru filtrare.
 * Astfel evităm hardcodarea string-urilor în mai multe locuri.
 */
enum class Category(
    val slug: String,
    val label: String,
    val emoji: String
) {
    TECHNOLOGY("tech", "Tehnologie", "💻"),
    SPORT("sport", "Sport", "⚽"),
    ECONOMY("economy", "Economie", "📈"),
    POLITICS("politics", "Politică", "🏛️"),
    SCIENCE("science", "Știință", "🔬"),
    LOCAL("local", "Local", "📍"),
    HEALTH("health", "Sănătate", "🏥"),
    ENTERTAINMENT("entertainment", "Divertisment", "🎬"),
    WORLD("world", "Internațional", "🌍");

    companion object {
        /** Conversia din slug-ul API/DB către enum. */
        fun fromSlug(slug: String): Category? =
            entries.find { it.slug.equals(slug, ignoreCase = true) }

        /** Toate slug-urile disponibile, util pentru query default. */
        val allSlugs: List<String> get() = entries.map { it.slug }
    }
}

