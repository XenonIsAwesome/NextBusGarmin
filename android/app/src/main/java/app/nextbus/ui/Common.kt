package app.nextbus.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** The operator's logo next to a big line number on the operator's colour. */
@Composable
fun LineBadge(line: String, operatorId: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OperatorStyle.logo(operatorId)?.let { OperatorLogo(it) }
        LineNumber(line, operatorId)
    }
}

/** On a white chip so dark logos stay readable in dark mode. */
@Composable
private fun OperatorLogo(@DrawableRes id: Int) {
    Box(
        Modifier.padding(end = 6.dp).size(44.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(id), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun LineNumber(line: String, operatorId: Int, modifier: Modifier = Modifier) {
    val bg = OperatorStyle.color(operatorId)
    Box(
        modifier
            .defaultMinSize(minWidth = 56.dp, minHeight = 44.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(line, color = OperatorStyle.onColor(bg), fontSize = 24.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

fun formatTime(epochMillis: Long?): String? =
    epochMillis?.let { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)) }
