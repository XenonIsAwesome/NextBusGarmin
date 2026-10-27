package app.nextbus.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.nextbus.BuildConfig
import app.nextbus.MainViewModel
import app.nextbus.MockWatchHub
import app.nextbus.R
import app.nextbus.RelayStatus
import app.nextbus.core.NextBusConfig
import app.nextbus.core.SavedSelection
import app.nextbus.protocol.Transport
import app.nextbus.data.IndexUpdateResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onAdd: () -> Unit) {
    val selections by vm.selections.collectAsState()
    val status by vm.status.collectAsState()
    val relayOn by vm.relayEnabled.collectAsState()
    var editing by remember { mutableStateOf<SavedSelection?>(null) }

    Scaffold(
        topBar = {
            val forced by vm.darkMode.collectAsState()
            val dark = forced ?: isSystemInDarkTheme()
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    Text(stringResource(R.string.dark_mode), style = MaterialTheme.typography.labelLarge)
                    Switch(dark, { vm.setDarkMode(it) }, Modifier.padding(horizontal = 12.dp))
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.add)) })
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { StatusCard(vm, status, relayOn) }
            item {
                Text(
                    stringResource(R.string.selections_title) + "  ·  " +
                        stringResource(R.string.capacity, selections.size, NextBusConfig.MAX_SELECTIONS),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (selections.isEmpty()) {
                item { Text(stringResource(R.string.empty_selections), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(selections, key = { it.id }) { sel ->
                SelectionRow(
                    sel, canUp = sel.id != selections.first().id, canDown = sel.id != selections.last().id,
                    onUp = { vm.move(sel.id, -1) }, onDown = { vm.move(sel.id, 1) },
                    onRemove = { vm.remove(sel.id) }, onEdit = { editing = sel },
                )
            }
        }
    }

    editing?.let { sel ->
        var text by remember(sel.id) { mutableStateOf(sel.stopNameEn) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.edit_name_title)) },
            text = {
                Column {
                    Text(sel.stopNameHe, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.edit_name_hint)) })
                }
            },
            confirmButton = { TextButton(onClick = { vm.renameStop(sel.id, text); editing = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatusCard(vm: MainViewModel, status: RelayStatus, relayOn: Boolean) {
    val context = LocalContext.current
    val info by vm.indexInfo.collectAsState()
    val indexMsg by vm.indexMessage.collectAsState()
    val checking by vm.indexChecking.collectAsState()
    val tethered by vm.tethered.collectAsState()
    val never = stringResource(R.string.never)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)

            val watchLine = when {
                !relayOn -> stringResource(R.string.relay_off)
                status.linkProblem != null -> status.linkProblem
                status.watchConnected -> stringResource(R.string.watch_connected, status.watchName ?: "")
                else -> stringResource(R.string.watch_not_connected)
            }
            val problem = !relayOn || status.linkProblem != null || !status.watchConnected
            Text(watchLine ?: "", color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (relayOn && status.watchConnected && status.watchAppInstalled == false) {
                Text(stringResource(R.string.watch_app_missing), color = MaterialTheme.colorScheme.error)
            }
            status.versionMismatch?.let {
                Text(
                    stringResource(if (it > Transport.PROTOCOL_VERSION) R.string.mismatch_phone_old else R.string.mismatch_watch_old),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status.linkProblem == stringResource(R.string.gcm_not_installed)) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.garmin.android.apps.connectmobile")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.open_play_store)) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.relay_label), Modifier.weight(1f))
                Switch(relayOn, { vm.setRelay(it) })
            }
            Fact(R.string.last_message, formatTime(status.lastWatchMessageAt) ?: never)
            Fact(R.string.last_sent, formatTime(status.lastSyncAt) ?: never)
            Fact(R.string.last_data, formatTime(status.lastFetchAt) ?: never)
            status.lastError?.let { Text(stringResource(R.string.error_prefix, it), color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.height(4.dp))
            info?.let { Text(stringResource(R.string.index_label, it.builtAt.take(10), it.stops), style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.checkIndexUpdate() }, enabled = !checking && BuildConfig.INDEX_MANIFEST_URL.isNotBlank()) {
                    Text(stringResource(R.string.index_check))
                }
                if (checking) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.height(24.dp).width(24.dp), strokeWidth = 2.dp)
                }
            }
            val msg = when (val r = if (BuildConfig.INDEX_MANIFEST_URL.isBlank()) IndexUpdateResult.NotConfigured else indexMsg) {
                IndexUpdateResult.NotConfigured -> stringResource(R.string.index_not_configured)
                IndexUpdateResult.UpToDate -> stringResource(R.string.index_up_to_date)
                is IndexUpdateResult.Updated -> stringResource(R.string.index_updated, r.builtAt.take(10))
                is IndexUpdateResult.Failed -> stringResource(R.string.index_failed, r.reason)
                null -> null
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (BuildConfig.DEBUG) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.simulator_mode))
                        Text(stringResource(R.string.simulator_mode_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(tethered, { vm.setTethered(it) })
                }
                val mock by vm.mockWatch.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.mock_watch))
                        Text(stringResource(R.string.mock_watch_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(mock, { vm.setMockWatch(it) })
                }
                if (mock) MockWatchCard()
            }
        }
    }
}

/** Debug: what the modelled watch app shows, with buttons for what a user does on the watch. */
@Composable
private fun MockWatchCard() {
    val st by MockWatchHub.state.collectAsState()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mock_watch), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { MockWatchHub.send(MockWatchHub.Command.OPEN) }, enabled = !st.open) { Text(stringResource(R.string.mock_open)) }
                Button(onClick = { MockWatchHub.send(MockWatchHub.Command.REFRESH) }, enabled = st.open) { Text(stringResource(R.string.mock_refresh)) }
                TextButton(onClick = { MockWatchHub.send(MockWatchHub.Command.CLOSE) }, enabled = st.open) { Text(stringResource(R.string.mock_close)) }
            }
            if (st.rows.isEmpty()) Text(stringResource(R.string.mock_no_rows), style = MaterialTheme.typography.bodySmall)
            st.rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(r.line, r.operatorId)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(r.stopName, style = MaterialTheme.typography.bodyMedium)
                        Text(r.status?.name ?: "-", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(r.times.joinToString(" · ").ifEmpty { "-" }, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Fact(label: Int, value: String) {
    Row {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SelectionRow(
    sel: SavedSelection, canUp: Boolean, canDown: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, onEdit: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LineBadge(sel.line, sel.operatorId)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(sel.stopNameEn, style = MaterialTheme.typography.titleSmall)
                Text(sel.stopNameHe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.to_dest, sel.destNameEn), style = MaterialTheme.typography.bodySmall)
                if (sel.nameIsGuess) {
                    Text(stringResource(R.string.name_guess), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Column {
                IconButton(onUp, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up)) }
                IconButton(onDown, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down)) }
            }
            IconButton(onRemove) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
        }
    }
}
