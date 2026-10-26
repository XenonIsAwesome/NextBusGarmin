package app.nextbus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.nextbus.MainViewModel
import app.nextbus.data.StopHit

/** Home -> Add (search) -> Lines at a stop. Back goes one step up. */
@Composable
fun NextBusRoot(vm: MainViewModel) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var stop by remember_stop()

    BackHandler(enabled = adding) {
        if (stop != null) stop = null else adding = false
    }
    when {
        !adding -> HomeScreen(vm, onAdd = { adding = true })
        stop == null -> AddScreen(vm, onBack = { adding = false }, onPick = { stop = it })
        else -> LinesScreen(vm, stop!!, onBack = { stop = null }, onDone = { stop = null; adding = false })
    }
}

@Composable
private fun remember_stop() = androidx.compose.runtime.remember { mutableStateOf<StopHit?>(null) }
