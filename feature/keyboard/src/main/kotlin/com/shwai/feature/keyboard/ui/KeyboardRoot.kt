package com.shwai.feature.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shwai.feature.keyboard.ime.KeyboardController
import com.shwai.sdk.orchestration.ActionProposal
import com.shwai.sdk.orchestration.AgentState
import com.shwai.sdk.orchestration.RiskLevel

private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

@Composable
internal fun KeyboardRoot(c: KeyboardController) {
    val state by c.agentState.collectAsState()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentPanel(state, c)
                KeyGrid(c)
            }
        }
    }
}

@Composable
private fun AgentPanel(state: AgentState, c: KeyboardController) {
    when (state) {
        AgentState.Idle -> Unit
        is AgentState.Working -> PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(state.label, Modifier.weight(1f))
                TextButton(onClick = c::dismiss) { Text("Cancel") }
            }
        }
        is AgentState.AwaitingConfirmation -> ConfirmationCard(state.proposal, c::approve, c::reject)
        is AgentState.Completed -> PanelCard {
            Text(state.insertText ?: state.text, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.insertText?.let { t -> Button(onClick = { c.replacePromptWith(t) }) { Text("Replace") } }
                TextButton(onClick = c::dismiss) { Text("Dismiss") }
            }
        }
        is AgentState.Failed -> PanelCard {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = c::dismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ConfirmationCard(p: ActionProposal, onApprove: () -> Unit, onReject: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val accent = if (p.risk == RiskLevel.EXTERNAL_SEND) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    val riskLabel = if (p.risk == RiskLevel.EXTERNAL_SEND) "Shares data outside ShwAI" else "Creates a draft. Nothing is sent."
    Column(
        Modifier.fillMaxWidth().heightIn(max = 260.dp)
            .clip(shape).border(1.5.dp, accent, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(riskLabel, style = MaterialTheme.typography.labelMedium, color = accent)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            p.fields.forEach { f ->
                Text(f.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(f.value, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Box(Modifier.height(6.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Reject") }
            Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text("Approve") }
        }
    }
}

@Composable
private fun KeyGrid(c: KeyboardController) {
    var shift by remember { mutableStateOf(false) }
    ROWS.forEachIndexed { i, row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (i == 1) 16.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (i == 2) Key("⇧", Modifier.weight(1.5f), highlighted = shift) { shift = !shift }
            row.forEach { ch ->
                val s = if (shift) ch.uppercase() else ch.toString()
                Key(s, Modifier.weight(1f)) { c.commit(s); shift = false }
            }
            if (i == 2) Key("⌫", Modifier.weight(1.5f)) { c.backspace() }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Key("AI", Modifier.weight(1.5f), accent = true) { c.ask() }
        Key(",", Modifier.weight(1f)) { c.commit(",") }
        Key("space", Modifier.weight(4f)) { c.commit(" ") }
        Key(".", Modifier.weight(1f)) { c.commit(".") }
        Key("⏎", Modifier.weight(1.5f)) { c.enter() }
    }
}

@Composable
private fun Key(
    label: String,
    modifier: Modifier,
    highlighted: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        accent -> scheme.primary
        highlighted -> scheme.secondaryContainer
        else -> scheme.surface
    }
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(8.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (accent) scheme.onPrimary else scheme.onSurface, fontWeight = if (accent) FontWeight.Bold else null)
    }
}
