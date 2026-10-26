package app.nextbus.ui

import androidx.compose.foundation.background
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

/** A big line number on the operator's colour. */
@Composable
fun LineBadge(line: String, operatorId: Int, modifier: Modifier = Modifier) {
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
