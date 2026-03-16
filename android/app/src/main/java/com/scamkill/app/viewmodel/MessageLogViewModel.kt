package com.scamkill.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scamkill.app.ScamKillApp
import com.scamkill.app.data.Conversation
import com.scamkill.app.data.SmsLogEntry
import com.scamkill.app.data.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MessagesUiState(
    val conversations: List<Conversation> = emptyList(),
    val scamLog: List<SmsLogEntry> = emptyList(),
    val selectedAddress: String? = null,
    val messages: List<SmsMessage> = emptyList(),
    val scamAnnotations: Map<Long, SmsLogEntry> = emptyMap(),
    val isLoading: Boolean = false,
)

class MessageLogViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ScamKillApp
    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state

    init {
        loadConversations()
    }

    fun loadConversations() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val convs = app.smsRepository.getConversations()
                val scamLog = app.preferences.getSmsLog()
                _state.value = _state.value.copy(
                    conversations = convs,
                    scamLog = scamLog,
                    isLoading = false,
                )
            } catch (e: Exception) {
                Log.e("ScamKill", "Failed to load conversations", e)
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun selectConversation(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(selectedAddress = address, isLoading = true)
            try {
                val messages = app.smsRepository.getMessagesForAddress(address)
                app.smsRepository.markAsRead(address)

                val scamLog = app.preferences.getSmsLog()
                val annotations = mutableMapOf<Long, SmsLogEntry>()
                for (msg in messages) {
                    val match = scamLog.find { entry ->
                        entry.from.endsWith(address.takeLast(10)) &&
                                entry.body == msg.body &&
                                entry.blocked
                    }
                    if (match != null) {
                        annotations[msg.id] = match
                    }
                }

                _state.value = _state.value.copy(
                    messages = messages,
                    scamAnnotations = annotations,
                    isLoading = false,
                )
            } catch (e: Exception) {
                Log.e("ScamKill", "Failed to load messages", e)
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun goBack() {
        _state.value = _state.value.copy(selectedAddress = null, messages = emptyList(), scamAnnotations = emptyMap())
        loadConversations()
    }

    fun sendReply(address: String, body: String) {
        if (body.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.smsRepository.sendSms(address, body)
                selectConversation(address)
            } catch (e: Exception) {
                Log.e("ScamKill", "Failed to send SMS", e)
            }
        }
    }

    fun isScamAddress(address: String): Boolean {
        return _state.value.scamLog.any { it.blocked && it.from.endsWith(address.takeLast(10)) }
    }
}
