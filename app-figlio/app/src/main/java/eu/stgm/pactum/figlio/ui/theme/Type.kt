package eu.stgm.pactum.figlio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Font di sistema: nessun file, nessun peso da scaricare. Si toccano quattro
// stili soltanto — quelli che oggi rendono tutto uguale a tutto.
// Gemello di app-genitore/ui/theme/Type.kt: stessi mattoni, respiro diverso.
private val Base = Typography()

val PactumTypography = Typography(
    // L'eroe della schermata, e mai due volte nella stessa: "4 h 12 min".
    displaySmall = Base.displaySmall.copy(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    // Il confronto della proposta pendente, i titoli degli stati vuoti forti.
    headlineSmall = Base.headlineSmall.copy(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    // TitoloSezione: oggi (Medium) è indistinguibile dal bodyLarge delle card.
    titleMedium = Base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    // Sopra-titoli scritti MAIUSCOLI nella stringa: "DOVE È FINITO IL TEMPO".
    labelMedium = Base.labelMedium.copy(
        letterSpacing = 0.6.sp,
    ),
)
