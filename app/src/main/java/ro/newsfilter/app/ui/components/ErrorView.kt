package ro.newsfilter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * View afișat când apare o eroare (rețea, server, parsare etc.).
 * Include mesajul de eroare și un buton „Reîncearcă" care redeclanșează încărcarea.
 *
 * @param message Mesajul de eroare afișat utilizatorului
 * @param isOffline True dacă eroarea este cauzată de lipsa internetului
 * @param onRetry Callback pentru butonul de reîncercare
 * @param retryLabel Textul butonului (ex. „Înapoi" pe ecranul de detaliu)
 */
@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isOffline: Boolean = false,
    retryLabel: String = "Reîncearcă",
    retryIcon: ImageVector? = Icons.Filled.Refresh
) {
    StatusView(
        icon = if (isOffline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
        title = if (isOffline) "Fără conexiune la internet" else "A apărut o eroare",
        message = message,
        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
        iconTint = MaterialTheme.colorScheme.onErrorContainer,
        actionLabel = retryLabel,
        actionIcon = retryIcon,
        onAction = onRetry,
        modifier = modifier
    )
}

/**
 * Stare generică centrată (gol / informativ / eroare): iconiță într-un cerc tonal,
 * titlu, mesaj și, opțional, un buton de acțiune.
 *
 * Folosită de ErrorView, de starea goală din Feed și de ecranul „Salvate",
 * ca toate aceste stări să arate consecvent în aplicație.
 */
@Composable
fun StatusView(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null
) {
    // BoxWithConstraints + heightIn(min = maxHeight): conținutul e centrat vertical,
    // dar rămâne derulabil pe ecrane mici / cu font mărit (verticalScroll singur
    // ar oferi înălțime infinită și ar anula centrarea).
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = iconContainerColor,
                modifier = Modifier.size(88.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = iconTint
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(onClick = onAction) {
                    if (actionIcon != null) {
                        Icon(
                            imageVector = actionIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = actionLabel)
                }
            }
        }
    }
}
