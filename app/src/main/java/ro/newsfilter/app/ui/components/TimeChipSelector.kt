package ro.newsfilter.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Opțiunile de timp disponibile în toată aplicația (minute). */
val TimeOptions = listOf(2, 5, 15)

/**
 * Selector de buget de timp sub forma unui control segmentat „pastilă”:
 * o șină rotunjită cu 3 segmente egale (2 / 5 / 15 min).
 *
 * Decizie tehnică: segmentul selectat devine o pastilă plină în culoarea
 * de accent; fundalul și textul fiecărui segment se animă cu
 * `animateColorAsState`, deci trecerea dintre opțiuni este lină.
 * Accesibilitate: rândul e un `selectableGroup`, iar fiecare segment are
 * rolul `RadioButton`, așa că TalkBack îl anunță ca alegere unică.
 *
 * Folosit în Feed, Onboarding și Setări.
 */
@Composable
fun TimeChipSelector(
    selectedMinutes: Int,
    onTimeSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TimeOptions.forEach { minutes ->
            TimeSegment(
                minutes = minutes,
                selected = minutes == selectedMinutes,
                onClick = { onTimeSelected(minutes) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TimeSegment(
    minutes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = tween<Color>(durationMillis = 250)
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spec,
        label = "segment_container"
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spec,
        label = "segment_content"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .background(container)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$minutes min",
            style = MaterialTheme.typography.labelLarge,
            color = content
        )
    }
}
