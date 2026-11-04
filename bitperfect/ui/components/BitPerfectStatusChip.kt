package com.example.bitperfect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitperfect.audio.BitPerfectStatus
import com.example.bitperfect.audio.DacFormatInfo

/**
 * UI Badge/Chip component showing the current Bit-Perfect & USB DAC status.
 */
@Composable
fun BitPerfectStatusChip(
    status: BitPerfectStatus,
    dacFormatInfo: DacFormatInfo,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, dotColor, textColor, textLabel) = when (status) {
        BitPerfectStatus.ACTIVE_BIT_PERFECT -> Quadruple(
            Color(0xFF1E2E1E),
            Color(0xFF4CAF50),
            MaterialTheme.colorScheme.primary,
            "BIT-PERFECT ${dacFormatInfo.bitDepth}-bit/${dacFormatInfo.sampleRate / 1000}kHz"
        )
        BitPerfectStatus.STANDARD_OUTPUT -> Quadruple(
            Color(0xFF2E2A1E),
            Color(0xFFFF9800),
            Color(0xFFFFB74D),
            "SHARED / ${dacFormatInfo.deviceName}"
        )
        BitPerfectStatus.DISABLED_INTERNAL -> Quadruple(
            Color(0xFF262626),
            Color(0xFF9E9E9E),
            Color(0xFFCCCCCC),
            "INTERNAL AUDIO / DIRECT"
        )
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = textLabel,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
