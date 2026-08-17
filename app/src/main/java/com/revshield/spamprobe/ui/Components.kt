package com.revshield.spamprobe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Colour-coded verdict chip — SPAM / SUSPECTED / FRAUD / UNKNOWN / NONE. */
@Composable
fun StatusChip(status: String?, modifier: Modifier = Modifier) {
    val c = statusColor(status)
    Text(
        text = statusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = c,
        modifier = modifier
            .background(c.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, c.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
