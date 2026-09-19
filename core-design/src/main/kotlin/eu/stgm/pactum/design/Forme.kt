package eu.stgm.pactum.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Forme delle due app (la baseline M3 è 4/8/12/16/28): le Card passano da 12
 * a 14, la scheda eroe e i dialoghi a 20.
 * I quadretti della striscia e le barre d'uso hanno la loro forma dentro il
 * componente: non passano di qui.
 */
val FormePactum = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
