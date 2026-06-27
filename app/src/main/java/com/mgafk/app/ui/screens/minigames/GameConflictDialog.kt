package com.mgafk.app.ui.screens.minigames

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mgafk.app.ui.GameConflict
import com.mgafk.app.ui.theme.Accent
import com.mgafk.app.ui.theme.StatusError
import com.mgafk.app.ui.theme.SurfaceBorder
import com.mgafk.app.ui.theme.SurfaceCard
import com.mgafk.app.ui.theme.SurfaceDark
import com.mgafk.app.ui.theme.TextMuted
import com.mgafk.app.ui.theme.TextPrimary

private fun gameName(game: String): String = when (game) {
    "crash" -> "Crash"
    "blackjack" -> "Blackjack"
    "mines" -> "Mines"
    else -> game.replaceFirstChar { it.uppercase() }
}

@Composable
fun GameConflictDialog(
    conflict: GameConflict,
    onForfeit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!conflict.loading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceCard)
                .border(1.5.dp, StatusError.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Game in progress",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = StatusError,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "You already have an active ${gameName(conflict.game)} game. Do you want to forfeit and start a new one?",
                fontSize = 13.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Forfeiting will lose your current bet.",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = StatusError.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (conflict.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Accent,
                    strokeWidth = 3.dp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Forfeiting...", fontSize = 12.sp, color = TextMuted)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Cancel
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceBorder)
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Cancel", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }
                    // Forfeit & retry
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StatusError)
                            .clickable(onClick = onForfeit)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Forfeit", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SurfaceDark)
                    }
                }
            }
        }
    }
}
