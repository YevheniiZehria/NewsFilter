package ro.newsfilter.app.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.newsfilter.app.domain.model.Category
import ro.newsfilter.app.ui.components.TimeChipSelector

/**
 * Ecranul de Onboarding.
 *
 * Decizie tehnică:
 *  - Categoriile sunt afișate într-o grilă cu 2 coloane (LazyVerticalGrid); fiecare
 *    „tile” are o stare selectată evidentă: contur în culoarea de accent, fundal
 *    nuanțat și o bifă.
 *  - Timpul implicit folosește același selector „pastilă” ca Feed-ul.
 *  - Butonul „Generează pachetul meu” e fixat jos (bottomBar), deci e mereu vizibil.
 *  - Validare: cel puțin o categorie (mesajul de eroare vine din ViewModel).
 *
 * Insets: Scaffold-ul aplică bara de status sus (contentWindowInsets implicit),
 * iar bara cu butonul aplică `navigationBarsPadding()` jos.
 */
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        AnimatedVisibility(visible = uiState.error != null) {
                            Text(
                                text = uiState.error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Button(
                            onClick = { viewModel.completeOnboarding(onOnboardingComplete) },
                            enabled = !uiState.isSaving,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                val count = uiState.selectedCategories.size
                                Text(
                                    text = if (count > 0) {
                                        "Generează pachetul meu ($count)"
                                    } else {
                                        "Generează pachetul meu"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 24.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            fullWidth {
                Column {
                    Text(
                        text = "Bun venit la NewsFilter",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Personalizează-ți știrile",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Alege domeniile care te interesează și cât timp ai la dispoziție. " +
                            "Îți pregătim un pachet de știri pe măsură.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            fullWidth {
                SectionTitle("Ce domenii te interesează?", modifier = Modifier.padding(top = 12.dp))
            }

            items(viewModel.availableCategories, key = { it.slug }) { category ->
                CategoryTile(
                    category = category,
                    selected = category.slug in uiState.selectedCategories,
                    onClick = { viewModel.toggleCategory(category.slug) }
                )
            }

            fullWidth {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    SectionTitle("Cât timp ai de obicei?")
                    Spacer(modifier = Modifier.height(12.dp))
                    TimeChipSelector(
                        selectedMinutes = uiState.defaultMinutes,
                        onTimeSelected = viewModel::setDefaultMinutes
                    )
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}

/**
 * Tile de categorie: emoji mare + nume. Selectat → contur de accent (2dp),
 * fundal nuanțat cu culoarea primară și bifă în colț. Tranziții animate.
 */
@Composable
private fun CategoryTile(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val baseSurface = MaterialTheme.colorScheme.surfaceContainerLow
    val container by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.10f).compositeOver(baseSurface) else baseSurface,
        label = "tile_container"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
        label = "tile_border"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        label = "tile_border_width"
    )

    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = BorderStroke(borderWidth, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        Box(modifier = Modifier.padding(14.dp)) {
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(text = category.emoji, fontSize = 26.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            AnimatedVisibility(
                visible = selected,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier
                        .size(22.dp)
                )
            }
        }
    }
}
