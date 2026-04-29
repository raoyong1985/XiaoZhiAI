package com.xiaozhi.aiservice.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.InputMode
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactsViewModel(
    private val contactsRepository: ContactsRepository
) : ViewModel() {

    val contacts: StateFlow<List<BotContact>> = contactsRepository.getContacts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            AppContainer.messageRepository.clearMessages(contactId)
        }
    }
    
    fun exportMessages(contactId: String, context: android.content.Context) {
        viewModelScope.launch {
            val messages = AppContainer.messageRepository.getMessages(contactId).value
            if (messages.isEmpty()) return@launch
            val text = messages.joinToString("\n") { msg ->
                val sender = if (msg.direction == com.xiaozhi.aiservice.domain.model.MessageDirection.SENT) "我" else "AI"
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
        val newMode = if (contact.inputMode == com.xiaozhi.aiservice.domain.model.InputMode.VOICE)
            com.xiaozhi.aiservice.domain.model.InputMode.TEXT
        else
            com.xiaozhi.aiservice.domain.model.InputMode.VOICE
        saveContact(contact.copy(inputMode = newMode))
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContactsViewModel(AppContainer.contactsRepository) as T
        }
    }
}

