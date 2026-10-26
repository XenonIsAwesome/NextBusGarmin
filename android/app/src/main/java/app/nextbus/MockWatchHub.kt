package app.nextbus

import app.nextbus.core.MockWatchRow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug "Mock watch": what the modelled watch shows (written by the relay service) and the buttons that drive it. */
object MockWatchHub {
    enum class Command { OPEN, CLOSE, REFRESH }

    data class State(val open: Boolean = false, val rows: List<MockWatchRow> = emptyList(), val generatedAt: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    fun publish(s: State) { _state.value = s }
    fun send(c: Command) { _commands.tryEmit(c) }
}
