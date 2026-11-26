package ro.newsfilter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/**
 * Dialog Material 3 pentru alegerea unei ore (format 24h).
 *
 * Decizie tehnică: Material 3 oferă componentele `TimePicker` (cadran) și
 * `TimeInput` (tastatură), dar nu și un dialog gata făcut în versiunea folosită.
 * Construim dialogul după specificația M3: suprafață `extraLarge`, titlu mic,
 * comutator cadran ↔ tastatură în stânga jos și acțiunile „Anulează" / „OK".
 *
 * @param initialHour Ora preselectată (0-23)
 * @param initialMinute Minutul preselectat (0-59)
 * @param onConfirm Apelat cu ora și minutul alese
 * @param onDismiss Apelat la anulare sau la atingerea în afara dialogului
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Alege ora"
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    var showKeyboardInput by rememberSaveable { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )

                if (showKeyboardInput) {
                    TimeInput(state = state)
                } else {
                    TimePicker(state = state)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = { showKeyboardInput = !showKeyboardInput }) {
                        Icon(
                            imageVector = if (showKeyboardInput) {
                                Icons.Outlined.Schedule
                            } else {
                                Icons.Outlined.Keyboard
                            },
                            contentDescription = if (showKeyboardInput) {
                                "Alege ora pe cadran"
                            } else {
                                "Introdu ora de la tastatură"
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Anulează")
                    }
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

/** Formatează ora în stilul `HH:mm` (ex. 08:05), independent de localizare. */
fun formatTime(hour: Int, minute: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", hour, minute)
