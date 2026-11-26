package ro.newsfilter.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.newsfilter.app.ui.theme.*

private val BadgeShape = RoundedCornerShape(8.dp)

/**
 * Badge-ul de categorie: emoji + label (ex: "💻 Tehnologie") pe un fundal
 * tonal în culoarea categoriei.
 *
 * Decizie tehnică: culorile de categorie sunt saturate (ex. galben, cyan) și,
 * folosite direct ca text, au contrast slab pe fundal deschis. De aceea
 * ajustăm tonul în funcție de tema curentă: pe Light Mode textul e închis
 * (amestec spre negru), pe Dark Mode e deschis (amestec spre alb), iar
 * fundalul are o opacitate puțin mai mare pe Dark ca să rămână vizibil.
 */
@Composable
fun CategoryChip(
    categorySlug: String,
    label: String,
    emoji: String,
    modifier: Modifier = Modifier
) {
    val colors = categoryBadgeColors(getCategoryColor(categorySlug))

    Text(
        text = "$emoji $label",
        style = MaterialTheme.typography.labelMedium,
        color = colors.content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(BadgeShape)
            .background(colors.container)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * Badge neutru pentru timpul de citire („X min"), cu aceeași formă și
 * înălțime ca badge-ul de categorie, ca cele două să stea echilibrat pe un rând.
 */
@Composable
fun ReadTimeBadge(
    minutes: Int,
    modifier: Modifier = Modifier,
    suffix: String = "min"
) {
    Row(
        modifier = modifier
            .clip(BadgeShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$minutes $suffix",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Pastila de categorie translucidă, folosită peste imagini (cardul Hero, detaliu).
 * Fundalul e suprafața temei cu opacitate ~90%, iar textul are culoarea categoriei,
 * precedat de un punct colorat — se citește bine pe orice fotografie.
 */
@Composable
fun CategoryPill(
    categorySlug: String,
    label: String,
    emoji: String,
    modifier: Modifier = Modifier
) {
    val accent = getCategoryColor(categorySlug)
    val colors = categoryBadgeColors(accent)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text = "$emoji $label",
            style = MaterialTheme.typography.labelMedium,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Culoarea de text a categoriei, cu contrast ajustat pentru tema curentă. */
@Composable
fun categoryContentColor(slug: String): Color = categoryBadgeColors(getCategoryColor(slug)).content

private data class BadgeColors(val container: Color, val content: Color)

@Composable
private fun categoryBadgeColors(accent: Color): BadgeColors {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (isDark) {
        BadgeColors(
            container = accent.copy(alpha = 0.24f),
            content = lerp(accent, Color.White, 0.45f)
        )
    } else {
        BadgeColors(
            container = accent.copy(alpha = 0.14f),
            content = lerp(accent, Color.Black, 0.45f)
        )
    }
}

/**
 * Returnează culoarea accent asociată fiecărei categorii.
 */
fun getCategoryColor(slug: String): Color = when (slug) {
    "tech" -> CategoryTech
    "sport" -> CategorySport
    "economy" -> CategoryEconomy
    "politics" -> CategoryPolitics
    "science" -> CategoryScience
    "local" -> CategoryLocal
    "health" -> CategoryHealth
    "entertainment" -> CategoryEntertainment
    "world" -> CategoryWorld
    else -> CategoryWorld
}
