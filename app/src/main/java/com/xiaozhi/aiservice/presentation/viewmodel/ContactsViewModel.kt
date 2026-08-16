package com.xiaozhi.aiservice.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.InputMode
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import com.xiaozhi.aiservice.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "ContactsViewModel"

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    private val messageRepository: MessageRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val contacts: StateFlow<List<BotContact>> = contactsRepository.getContacts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent

    // 每个联系人的最后一条消息
    val lastMessages: StateFlow<Map<String, ChatMessage>> = contacts.map { contactList ->
        contactList.associate { contact ->
            contact.id to (messageRepository.getLastMessage(contact.id).value ?: ChatMessage(
                id = "", content = "", direction = MessageDirection.RECEIVED
            ))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun saveContact(contact: BotContact) {
        viewModelScope.launch {
            contactsRepository.saveContact(contact)
        }
    }

    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            contactsRepository.deleteContact(contactId)
        }
    }

    fun clearMessages(contactId: String) {
        viewModelScope.launch {
            messageRepository.clearMessages(contactId)
        }
    }
    
    fun exportMessages(contactId: String, context: android.content.Context) {
        viewModelScope.launch {
            val messages = messageRepository.getMessages(contactId).value
            if (messages.isEmpty()) return@launch
            val text = messages.joinToString("\n") { msg ->
                val sender = if (msg.direction == MessageDirection.SENT) "我" else "AI"
                "[${sender}]: ${msg.content}"
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "聊天记录")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "导出聊天记录"))
        }
    }
    
    fun toggleInputMode(contact: BotContact) {
        val newMode = if (contact.inputMode == InputMode.VOICE)
            InputMode.TEXT
        else
            InputMode.VOICE
        saveContact(contact.copy(inputMode = newMode))
    }
}
