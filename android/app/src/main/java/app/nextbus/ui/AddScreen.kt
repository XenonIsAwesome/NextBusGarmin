package app.nextbus.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nextbus.MainViewModel
import app.nextbus.R
import app.nextbus.core.NextBusConfig
import app.nextbus.data.AddResult
import app.nextbus.data.LineOption
import app.nextbus.data.StopHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(vm: MainViewModel, onBack: () -> Unit, onPick: (StopHit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StopHit>>(emptyList()) }
    var nearby by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            nearby = false
            delay(200) // debounce typing
            results = vm.searchStops(query)
        } else if (!nearby) {
            results = emptyList()
        }
    }

    fun loadNearby() {
        val loc = lastLocation(context)
        if (loc == null) {
            Toast.makeText(context, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            query = ""
            nearby = true
            results = vm.nearbyStops((loc.latitude * 1e6).toInt(), (loc.longitude * 1e6).toInt())
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadNearby() else Toast.makeText(context, R.string.location_denied, Toast.LENGTH_SHORT).show()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.add_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.search_hint)) },
            )
            AssistChip(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) loadNearby()
                    else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                },
                label = { Text(stringResource(R.string.near_me)) },
                leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (results.isEmpty() && (query.length >= 2 || nearby)) {
                Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.code }) { StopRow(it) { onPick(it) } }
            }
        }
    }
}

@Composable
private fun StopRow(stop: StopHit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stop.nameEn, style = MaterialTheme.typography.titleSmall)
                Text(stop.nameHe, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${stop.cityEn} · ${stringResource(R.string.stop_code_fmt, stop.code)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            stop.distanceM?.let { Text(stringResource(R.string.meters, it), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinesScreen(vm: MainViewModel, stop: StopHit, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember(stop.code) { mutableStateOf<List<LineOption>?>(null) }
    LaunchedEffect(stop.code) { lines = vm.linesAt(stop) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.lines_title, stop.nameEn)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        val list = lines
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp)) {
            Text(stop.nameHe, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            if (list != null && list.isEmpty()) Text(stringResource(R.string.no_lines))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.orEmpty(), key = { "${it.line}/${it.operatorId}/${it.destCode}" }) { line ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                when (vm.add(stop, line)) {
                                    AddResult.ADDED -> { Toast.makeText(context, R.string.added, Toast.LENGTH_SHORT).show(); onDone() }
                                    AddResult.DUPLICATE -> Toast.makeText(context, R.string.duplicate, Toast.LENGTH_SHORT).show()
                                    AddResult.FULL -> Toast.makeText(context, context.getString(R.string.full, NextBusConfig.MAX_SELECTIONS), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            LineBadge(line.line, line.operatorId)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(line.operatorEn, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.to_dest, line.destEn), style = MaterialTheme.typography.titleSmall)
                                Text(line.destHe, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Approximate location without Play Services: the freshest last-known fix of any provider. */
@SuppressLint("MissingPermission")
private fun lastLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()
}
