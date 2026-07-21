package com.qcs.geniechat

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.qcs.geniechat.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ChatViewModel by viewModels()
    private val adapter = ChatAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messageList.adapter = adapter

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reset) {
                viewModel.resetConversation()
                true
            } else {
                false
            }
        }

        // Send is the button only: the input is multi-line, which makes the IME
        // show Enter-as-newline and swallow IME_ACTION_SEND entirely.
        binding.sendButton.setOnClickListener { onSendOrStop() }

        // Dictation is wired up in Dictation.kt; hidden until a Whisper bundle
        // is present, since without one the button can only ever fail.
        binding.micButton.visibility = android.view.View.GONE

        observe()
    }

    private fun onSendOrStop() {
        if ((viewModel.state.value as? UiState.Ready)?.busy == true) {
            viewModel.stop()
            return
        }
        val text = binding.input.text.toString().trim()
        if (text.isEmpty()) return
        binding.input.setText("")
        viewModel.send(text)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { items ->
                        adapter.submitList(items) {
                            if (items.isNotEmpty()) {
                                binding.messageList.scrollToPosition(items.size - 1)
                            }
                        }
                    }
                }
                launch { viewModel.status.collect { binding.statusText.text = it } }
                launch { viewModel.state.collect { render(it) } }
            }
        }
    }

    private fun render(state: UiState) = with(binding) {
        val showOverlay = state !is UiState.Ready
        overlay.visibility = if (showOverlay) android.view.View.VISIBLE else android.view.View.GONE
        overlayProgress.visibility =
            if (state is UiState.Loading) android.view.View.VISIBLE else android.view.View.GONE
        overlayButton.visibility =
            if (state is UiState.Failed) android.view.View.VISIBLE else android.view.View.GONE
        overlayButton.setOnClickListener { viewModel.retry() }

        when (state) {
            is UiState.Loading -> overlayText.setText(R.string.loading_model)
            is UiState.ModelMissing ->
                overlayText.text = getString(R.string.model_missing, state.expectedPath)
            is UiState.Failed ->
                overlayText.text = getString(R.string.model_failed, state.message)
            is UiState.Ready -> {
                sendButton.setText(if (state.busy) R.string.stop else R.string.send)
                input.isEnabled = !state.busy
            }
        }
    }
}
