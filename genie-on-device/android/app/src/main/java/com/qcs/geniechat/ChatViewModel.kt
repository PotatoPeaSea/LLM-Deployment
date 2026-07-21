package com.qcs.geniechat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the chat list shows. [streaming] marks the bubble currently being filled in. */
data class ChatItem(val role: Role, val text: String, val streaming: Boolean = false)

sealed interface UiState {
    data object Loading : UiState
    data class ModelMissing(val expectedPath: String) : UiState
    data class Failed(val message: String) : UiState
    data class Ready(val busy: Boolean) : UiState
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private var engine: ChatEngine? = null

    init {
        loadModel()
    }

    private fun loadModel() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            val created = ChatEngine.create(getApplication())
            if (created == null) {
                _state.value = UiState.ModelMissing(ModelStore.expectedPath(getApplication()))
                return@launch
            }
            _status.value = "Loading model onto the NPU..."
            try {
                // Blocking, ~10-40s: this maps the context binaries and allocates
                // the KV cache on the DSP. It happens exactly once per process.
                val ms = withContext(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    created.load()
                    System.currentTimeMillis() - t0
                }
                engine = created
                _status.value = "Ready - ${created.contextLength} token context, loaded in ${ms / 1000.0}s"
                _state.value = UiState.Ready(busy = false)
            } catch (e: Throwable) {
                _state.value = UiState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun retry() = loadModel()

    fun send(text: String) {
        val active = engine ?: return
        if ((_state.value as? UiState.Ready)?.busy != false) return

        _messages.value = _messages.value + ChatItem(Role.USER, text) +
            ChatItem(Role.ASSISTANT, "", streaming = true)
        _state.value = UiState.Ready(busy = true)
        _status.value = "Generating..."

        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    val partial = StringBuilder()
                    // Fires on the worker thread for every fragment Genie emits;
                    // StateFlow assignment is thread-safe, the UI collects on main.
                    active.ask(text) { fragment ->
                        partial.append(fragment)
                        updateStreamingBubble(partial.toString())
                    }
                }
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                _status.value = "%.1fs".format(secs)
            } catch (e: Throwable) {
                updateStreamingBubble("[error] ${e.message}")
                _status.value = "Generation failed"
            } finally {
                finishStreamingBubble()
                _state.value = UiState.Ready(busy = false)
            }
        }
    }

    fun stop() = engine?.abort()

    fun resetConversation() {
        engine?.reset()
        _messages.value = emptyList()
        _status.value = "Conversation cleared"
    }

    private fun updateStreamingBubble(text: String) {
        _messages.value = _messages.value.toMutableList().also { list ->
            val i = list.indexOfLast { it.streaming }
            if (i >= 0) list[i] = list[i].copy(text = text)
        }
    }

    private fun finishStreamingBubble() {
        _messages.value = _messages.value.map {
            if (it.streaming) it.copy(streaming = false, text = it.text.trim()) else it
        }
    }

    override fun onCleared() {
        engine?.close()
        engine = null
        super.onCleared()
    }
}
