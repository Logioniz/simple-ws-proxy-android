package com.logioniz.simplewsproxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logioniz.simplewsproxy.R
import com.logioniz.simplewsproxy.proxy.ProxyState
import com.logioniz.simplewsproxy.proxy.StatusLevel

private val PlayGreen = Color(0xFF2E7D32)
private val StopRed = Color(0xFFC62828)

/**
 * The main control screen: a single large circular button that toggles the
 * proxy on/off, with the latest status message rendered underneath.
 */
@Composable
fun PlayScreen(
    onToggle: (start: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running by ProxyState.running.collectAsStateWithLifecycle()
    val status by ProxyState.status.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(if (running) StopRed else PlayGreen)
                .clickable { onToggle(!running) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_play),
                contentDescription = stringResource(if (running) R.string.play_stop else R.string.play_start),
                tint = Color.White,
                modifier = Modifier.size(72.dp),
            )
        }

        val statusText = status.text.ifEmpty { stringResource(R.string.play_idle) }
        val statusColor = when (status.level) {
            StatusLevel.SUCCESS -> PlayGreen
            StatusLevel.ERROR -> StopRed
            StatusLevel.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = statusText,
            color = statusColor,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 32.dp),
        )
    }
}
