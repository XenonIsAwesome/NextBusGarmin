package app.nextbus.ui

import androidx.compose.ui.graphics.Color
import app.nextbus.R

/**
 * Colour of a line number by operator id (curlbus/Ministry ids), for the phone UI. The values are
 * the dominant colour of each operator's logo, the same ones the watch uses on 390 px screens
 * (watch/source/Operators.mc, `python3 tools/logos/dominant_colors.py --table`).
 */
object OperatorStyle {
    private val colors = mapOf(
        3 to 0x7ED19F, 4 to 0x2F83CC, 5 to 0x205CAB, 6 to 0x8A5A2B, 7 to 0xEC2227, 8 to 0xC0392B,
        10 to 0x1ABC9C, 14 to 0xF7BA69, 15 to 0xFF8200, 16 to 0x008A5E, 18 to 0x99CA3C, 20 to 0x16A085,
        21 to 0x5D6D7E, 23 to 0x884EA0, 24 to 0x7D6608,
    )

    private val logos = mapOf(
        3 to R.drawable.logo_egged, 4 to R.drawable.logo_egged_taavura, 5 to R.drawable.logo_dan,
        7 to R.drawable.logo_ntt, 14 to R.drawable.logo_nateev, 15 to R.drawable.logo_metropoline,
        16 to R.drawable.logo_superbus, 18 to R.drawable.logo_kavim,
    )

    /** The operator's logo (tools/logos), or null for operators without one. */
    fun logo(operatorId: Int): Int? = logos[operatorId]

    fun color(operatorId: Int): Color = Color(0xFF000000L or (colors[operatorId] ?: 0x607D8B).toLong())

    /** Black or white, whichever reads better on [background]. */
    fun onColor(background: Color): Color =
        if (background.red * 0.299f + background.green * 0.587f + background.blue * 0.114f > 0.6f) Color.Black else Color.White
}
