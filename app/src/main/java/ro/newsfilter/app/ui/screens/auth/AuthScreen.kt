package ro.newsfilter.app.ui.screens.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val FieldShape = RoundedCornerShape(14.dp)

/**
 * Ecranul de autentificare, în stilul editorial al aplicației.
 *
 * - Comutator „Autentificare / Cont nou” (pastilă, ca selectorul de timp);
 * - câmpuri `OutlinedTextField` cu `isError` + text de eroare în română,
 *   validate în timp real (după părăsirea câmpului) și la trimitere;
 * - parolă cu buton de afișare / ascundere;
 * - „Continuă cu Google” (Credential Manager) + selector demonstrativ de rezervă.
 *
 * Ecranul este „stateless”: starea vine din [AuthViewModel], iar fluxul Google
 * (care are nevoie de Activity) este pornit prin [onGoogleClick].
 */
@Composable
fun AuthScreen(
    state: AuthUiState,
    onModeChange: (AuthMode) -> Unit,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onFieldBlur: (AuthField) -> Unit,
    onSubmit: () -> Unit,
    onGoogleClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val isRegister = state.mode == AuthMode.REGISTER

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                BrandHeader()
                Spacer(modifier = Modifier.height(32.dp))

                ModeSelector(selected = state.mode, onSelect = onModeChange, enabled = !state.isBusy)
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = if (isRegister) "Creează-ți contul" else "Bine ai revenit",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isRegister) {
                        "Durează mai puțin de un minut. Preferințele tale te vor aștepta pe orice dispozitiv."
                    } else {
                        "Intră în cont pentru pachetul tău personalizat de știri."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                )

                // === Nume (doar la creare cont) ===
                AnimatedVisibility(
                    visible = isRegister,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        AuthTextField(
                            value = state.name,
                            onValueChange = onNameChange,
                            label = "Nume",
                            icon = Icons.Outlined.Person,
                            error = state.nameError,
                            enabled = !state.isBusy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onBlur = { onFieldBlur(AuthField.NAME) }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // === Email ===
                AuthTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = "Email",
                    icon = Icons.Outlined.Email,
                    error = state.emailError,
                    enabled = !state.isBusy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onBlur = { onFieldBlur(AuthField.EMAIL) }
                )
                Spacer(modifier = Modifier.height(12.dp))

                // === Parolă ===
                AuthTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = "Parolă",
                    icon = Icons.Outlined.Lock,
                    error = state.passwordError,
                    helper = if (isRegister) "Minimum ${AuthValidator.MIN_PASSWORD_LENGTH} caractere" else null,
                    enabled = !state.isBusy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    onDone = {
                        focusManager.clearFocus()
                        onSubmit()
                    },
                    onBlur = { onFieldBlur(AuthField.PASSWORD) },
                    visualTransformation = if (state.isPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(
                                imageVector = if (state.isPasswordVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = if (state.isPasswordVisible) "Ascunde parola" else "Arată parola"
                            )
                        }
                    }
                )

                // === Eroare de la server / validare globală ===
                AnimatedVisibility(visible = state.errorMessage != null) {
                    ErrorBanner(message = state.errorMessage.orEmpty())
                }

                Spacer(modifier = Modifier.height(24.dp))

                // === Buton principal ===
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onSubmit()
                    },
                    enabled = !state.isBusy,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            text = if (isRegister) "Creează contul" else "Intră în cont",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                OrDivider()

                // === Google ===
                OutlinedButton(
                    onClick = onGoogleClick,
                    enabled = !state.isBusy,
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (state.isGoogleLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    } else {
                        GoogleMark(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.size(12.dp))
                        Text("Continuă cu Google", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = if (isRegister) "Ai deja cont? Autentifică-te" else "Nu ai cont? Creează unul acum",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable(enabled = !state.isBusy) {
                            onModeChange(if (isRegister) AuthMode.LOGIN else AuthMode.REGISTER)
                        }
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}

/** Sigla + numele aplicației + tagline. */
@Composable
private fun BrandHeader() {
    Column {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Newspaper,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "NewsFilter",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Știrile care contează, în timpul pe care îl ai.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Comutator „pastilă” între cele două moduri, în stilul `TimeChipSelector`. */
@Composable
private fun ModeSelector(selected: AuthMode, onSelect: (AuthMode) -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(AuthMode.LOGIN to "Autentificare", AuthMode.REGISTER to "Cont nou").forEach { (mode, label) ->
            val isSelected = mode == selected
            val container by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = tween(250),
                label = "mode_container"
            )
            val content by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(250),
                label = "mode_content"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(container)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { onSelect(mode) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}

/**
 * Câmp de text cu validare: `isError` + mesajul de eroare ca `supportingText`.
 * [onBlur] se apelează când câmpul pierde focusul după ce l-a avut.
 */
@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    error: String?,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    onBlur: () -> Unit,
    helper: String? = null,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = when {
            trailing != null -> trailing
            error != null -> {
                { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) }
            }
            else -> null
        },
        isError = error != null,
        supportingText = (error ?: helper)?.let { text -> { Text(text) } },
        singleLine = true,
        enabled = enabled,
        shape = FieldShape,
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(
            onNext = { onNext?.invoke() },
            onDone = { onDone?.invoke() }
        ),
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    if (value.isNotEmpty()) onBlur()
                }
            }
    )
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "sau",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Simbolul „G” în cele patru culori Google, desenat vectorial pe Canvas
 * (fără resurse grafice externe).
 */
@Composable
private fun GoogleMark(modifier: Modifier = Modifier) {
    val blue = Color(0xFF4285F4)
    val green = Color(0xFF34A853)
    val yellow = Color(0xFFFBBC05)
    val red = Color(0xFFEA4335)
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.22f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        fun arc(color: Color, start: Float, sweep: Float) = drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        arc(blue, 0f, 50f)
        arc(green, 50f, 85f)
        arc(yellow, 135f, 85f)
        arc(red, 220f, 95f)
        // Bara orizontală a literei „G”
        drawLine(
            color = blue,
            start = Offset(size.width / 2, size.height / 2),
            end = Offset(size.width - inset / 2, size.height / 2),
            strokeWidth = stroke
        )
    }
}
