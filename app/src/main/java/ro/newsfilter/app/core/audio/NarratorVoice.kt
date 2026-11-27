package ro.newsfilter.app.core.audio

/**
 * Presetul de voce folosit la citirea rezumatelor prin Text-to-Speech.
 *
 * [NARRATOR] este un stil dramatic (voce configurabilă, ton mai grav, ritm mai lent,
 * pauză după titlu), configurat prin `narrator.*` din `local.properties`.
 */
enum class NarratorVoice(val label: String) {
    STANDARD("Standard"),
    NARRATOR("Narator");

    fun next(): NarratorVoice = entries[(ordinal + 1) % entries.size]
}
