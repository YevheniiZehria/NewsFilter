package ro.newsfilter.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.newsfilter.app.domain.model.Currency
import ro.newsfilter.app.domain.model.MarketPairs

private val FieldShape = RoundedCornerShape(12.dp)

/**
 * Convertorul valutar: două rânduri „[sumă] | [MONEDĂ (denumire) ▼]” și butonul ⇅.
 *
 * Se reține doar câmpul editat ultima dată; celălalt este calculat la fiecare
 * recompunere din [rate] (cursul afișat în widget: live sau punctul de sub deget),
 * deci se actualizează singur când se schimbă cursul. Sus: suma × curs; jos: suma ÷ curs.
 *
 * @param rate câte unități [to] valorează 1 [from]; null = curs indisponibil
 * @param onPairChange perechea stocată + direcția, aleasă din listele derulante
 */
@Composable
fun CurrencyConverter(
    from: Currency,
    to: Currency,
    rate: Double?,
    onPairChange: (pair: String, inverted: Boolean) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editedTop by rememberSaveable { mutableStateOf(true) }
    var editedText by rememberSaveable { mutableStateOf("1") }

    val amount = parseAmount(editedText)
    val derived = when {
        rate == null || rate <= 0 || amount == null -> ""
        editedTop -> formatAmount(amount * rate, to)
        else -> formatAmount(amount / rate, from)
    }
    val topValue = if (editedTop) editedText else derived
    val bottomValue = if (editedTop) derived else editedText

    fun select(newFrom: Currency, newTo: Currency) {
        MarketPairs.pairFor(newFrom, newTo)?.let { (pair, inverted) -> onPairChange(pair, inverted) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ConverterRow(
            value = topValue,
            currency = from,
            options = Currency.entries,
            onValueChange = {
                editedTop = true
                editedText = sanitizeAmountInput(it)
            },
            onCurrencySelect = { newFrom ->
                val newTo = if (MarketPairs.pairFor(newFrom, to) != null) to else MarketPairs.counterparts(newFrom).first()
                select(newFrom, newTo)
            },
            label = "Sumă în ${from.nameRo}"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.Center
        ) {
            FilledTonalIconButton(
                onClick = onSwap,
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "Inversează monedele" }
            ) {
                Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        ConverterRow(
            value = bottomValue,
            currency = to,
            options = MarketPairs.counterparts(from),
            onValueChange = {
                editedTop = false
                editedText = sanitizeAmountInput(it)
            },
            onCurrencySelect = { newTo -> select(from, newTo) },
            label = "Sumă în ${to.nameRo}"
        )
    }
}

/** Un rând al convertorului: câmpul numeric în stânga, lista derulantă a monedei în dreapta. */
@Composable
private fun ConverterRow(
    value: String,
    currency: Currency,
    options: List<Currency>,
    onValueChange: (String) -> Unit,
    onCurrencySelect: (Currency) -> Unit,
    label: String
) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(FieldShape)
            .border(1.dp, outline.copy(alpha = 0.6f), FieldShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFeatureSettings = "tnum"
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier
                .weight(0.45f)
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .semantics { contentDescription = label }
        )
        VerticalDivider(
            color = outline.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxHeight()
        )
        CurrencyDropdown(
            currency = currency,
            options = options,
            onSelect = onCurrencySelect,
            modifier = Modifier.weight(0.55f)
        )
    }
}

/** „USD (Dolar SUA) ▼” + meniul cu monedele disponibile. */
@Composable
private fun CurrencyDropdown(
    currency: Currency,
    options: List<Currency>,
    onSelect: (Currency) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.DropdownList,
                    onClickLabel = "Alege moneda",
                    enabled = options.size > 1 || options.firstOrNull() != currency
                ) { expanded = true }
                .padding(start = 12.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${currency.code} (${currency.nameRo})",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${option.code} (${option.nameRo})",
                            fontWeight = if (option == currency) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        expanded = false
                        if (option != currency) onSelect(option)
                    }
                )
            }
        }
    }
}
