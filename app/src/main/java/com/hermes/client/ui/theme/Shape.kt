package com.hermes.client.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Chamfered "cyberdeck" geometry: cut corners instead of rounded ones. Larger containers cut
// only the top-start and bottom-end corners — the diagonal notch reads as sci-fi hardware
// without wasting space on all four sides. These feed Material3 defaults (cards, chips, menus,
// text fields, sheets, FAB) app-wide; bespoke shapes live in Cyber.kt.

val HermesShapes = Shapes(
    extraSmall = CutCornerShape(3.dp),
    small = CutCornerShape(5.dp),
    medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    large = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
    extraLarge = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
)
