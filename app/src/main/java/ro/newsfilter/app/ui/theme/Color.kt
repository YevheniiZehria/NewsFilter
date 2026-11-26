package ro.newsfilter.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta de culori „editorială” a NewsFilter.
 *
 * Decizie tehnică: o identitate de brand fixă (fără Dynamic Color), inspirată de
 * publicațiile digitale moderne: fundal alb-cald aerisit, carduri albe pure care
 * „plutesc” deasupra fundalului și un singur accent — albastru editorial profund.
 * Restul paletei e neutru (gri-ardezie), ca textul și imaginile să fie în prim-plan.
 *
 * Maparea rolurilor Material 3 folosite de componente:
 *   background / surface        → fundalul ecranelor
 *   surfaceContainerLow         → cardurile de știri necitite (alb pur în Light)
 *   surfaceContainer            → cardurile citite, bara de navigare
 *   surfaceContainerHighest     → chip-uri, shimmer, placeholder imagini
 */

// === Light Theme ===
val PrimaryLight = Color(0xFF1E3A8A)          // Albastru editorial profund
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFDCE4FF)
val OnPrimaryContainerLight = Color(0xFF0B1F5C)

val SecondaryLight = Color(0xFF475569)        // Gri-ardezie
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFE2E8F0)
val OnSecondaryContainerLight = Color(0xFF0F172A)

val TertiaryLight = Color(0xFF9A3412)         // Accent cald, folosit rar
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFFFEDD5)
val OnTertiaryContainerLight = Color(0xFF431407)

val ErrorLight = Color(0xFFB91C1C)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFEE2E2)
val OnErrorContainerLight = Color(0xFF450A0A)

val BackgroundLight = Color(0xFFF8F9FA)       // Alb-cald
val OnBackgroundLight = Color(0xFF111827)     // Cerneală
val SurfaceLight = Color(0xFFF8F9FA)
val OnSurfaceLight = Color(0xFF111827)
val SurfaceVariantLight = Color(0xFFEEF0F3)
val OnSurfaceVariantLight = Color(0xFF4B5563)
val OutlineLight = Color(0xFF9CA3AF)
val OutlineVariantLight = Color(0xFFE5E7EB)
// Nivelurile de „container" Material 3 (Card, NavigationBar, dialoguri, TimePicker).
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFFFFFFF)   // Carduri albe pure
val SurfaceContainerLight = Color(0xFFF1F3F5)
val SurfaceContainerHighLight = Color(0xFFFFFFFF)  // Dialoguri
val SurfaceContainerHighestLight = Color(0xFFE9ECEF)

// === Dark Theme ===
val PrimaryDark = Color(0xFF9DB7F5)           // Albastru editorial, luminat pentru contrast
val OnPrimaryDark = Color(0xFF0B1F5C)
val PrimaryContainerDark = Color(0xFF1E3A8A)
val OnPrimaryContainerDark = Color(0xFFDCE4FF)

val SecondaryDark = Color(0xFFCBD5E1)
val OnSecondaryDark = Color(0xFF1E293B)
val SecondaryContainerDark = Color(0xFF334155)
val OnSecondaryContainerDark = Color(0xFFE2E8F0)

val TertiaryDark = Color(0xFFFDBA74)
val OnTertiaryDark = Color(0xFF431407)
val TertiaryContainerDark = Color(0xFF7C2D12)
val OnTertiaryContainerDark = Color(0xFFFFEDD5)

val ErrorDark = Color(0xFFFCA5A5)
val OnErrorDark = Color(0xFF450A0A)
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Color(0xFFFEE2E2)

val BackgroundDark = Color(0xFF0F1115)        // Aproape negru, ușor rece
val OnBackgroundDark = Color(0xFFE5E7EB)
val SurfaceDark = Color(0xFF0F1115)
val OnSurfaceDark = Color(0xFFE5E7EB)
val SurfaceVariantDark = Color(0xFF262A31)
val OnSurfaceVariantDark = Color(0xFF9CA3AF)
val OutlineDark = Color(0xFF6B7280)
val OutlineVariantDark = Color(0xFF2A2F37)
val SurfaceContainerLowestDark = Color(0xFF0B0D10)
val SurfaceContainerLowDark = Color(0xFF1A1D23)    // Carduri
val SurfaceContainerDark = Color(0xFF16191E)
val SurfaceContainerHighDark = Color(0xFF22262D)
val SurfaceContainerHighestDark = Color(0xFF2C313A)

// === Culori accent pentru categorii ===
val CategoryTech = Color(0xFF2196F3)
val CategorySport = Color(0xFF4CAF50)
val CategoryEconomy = Color(0xFFFF9800)
val CategoryPolitics = Color(0xFF9C27B0)
val CategoryScience = Color(0xFF00BCD4)
val CategoryLocal = Color(0xFFE91E63)
val CategoryHealth = Color(0xFFFF5722)
val CategoryEntertainment = Color(0xFFFFC107)
val CategoryWorld = Color(0xFF607D8B)

