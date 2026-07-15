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

    /** 唤醒模板：contactId → base64 */
    val wakeTemplates: StateFlow<Map<String, String>> = contactsRepository.getWakeTemplates()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val contacts: StateFlow<List<BotContact>> = contactsRepository.getContacts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 自动训练中的联系人 ID */
    private val _trainingContactId = MutableStateFlow<String?>(null)
    val trainingContactId: StateFlow<String?> = _trainingContactId

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

    fun saveWakeTemplate(contactId: String, templateBase64: String) {
        viewModelScope.launch {
            contactsRepository.saveWakeTemplate(contactId, templateBase64)
        }
    }

    fun deleteWakeTemplate(contactId: String) {
        viewModelScope.launch {
            contactsRepository.deleteWakeTemplate(contactId)
        }
    }

    private var kwsEngine: com.xiaozhi.aiservice.data.audio.KwsEngine? = null

    fun trainWakeTemplate(contactId: String, wakeWord: String) {
        if (_trainingContactId.value != null) return
        if (wakeWord.isBlank()) {
            _toastEvent.tryEmit("请先输入唤醒词")
            return
        }
        _trainingContactId.value = contactId
        viewModelScope.launch {
            try {
                Logger.w(TAG, "KWS 自动训练: contact=$contactId, word=「$wakeWord」")
                _toastEvent.tryEmit("正在用 Edge TTS 合成唤醒词「$wakeWord」…")

                // 初始化 KWS 引擎
                if (kwsEngine == null) {
                    kwsEngine = com.xiaozhi.aiservice.data.audio.KwsEngine()
                    kwsEngine!!.init(context)
                }

                val edgeTts = com.xiaozhi.aiservice.data.audio.EdgeTtsSynthesizer()

                // 用 Edge TTS 合成多种语速变体
                val pcmSamples = mutableListOf<ByteArray>()

                // 检测重复唤醒词（如"悠悠悠悠"→"悠悠 悠悠"），在中间加停顿
                val splitAfter = if (wakeWord.length >= 4 && wakeWord.length % 2 == 0) {
                    val half = wakeWord.length / 2
                    if (wakeWord.substring(0, half) == wakeWord.substring(half)) half - 1 else -1
                } else -1

                // 正常语速
                val normal = edgeTts.synthesize(wakeWord, splitAfter)
                if (normal != null) pcmSamples.add(normal)

                // 慢速语速（修改 SSML prosody）
                val slow = edgeTts.synthesize(wakeWord)
                if (slow != null) pcmSamples.add(slow)

                // 再合成一次，增加样本量（Edge TTS 每次合成略有差异）
                val extra = edgeTts.synthesize(wakeWord)
                if (extra != null) pcmSamples.add(extra)

                if (pcmSamples.size < 2) {
                    Logger.w(TAG, "KWS 训练失败: Edge TTS 合成次数不足")
                    _toastEvent.tryEmit("Edge TTS 合成失败，请检查网络连接后重试")
                    return@launch
                }

                // 提取 embedding 并平均后添加模板
                val success = kwsEngine!!.addTemplateFromMultiplePcm(contactId, wakeWord, pcmSamples)
                if (success) {
                    Logger.i(TAG, "✅ KWS 模板训练成功: $contactId 「$wakeWord」")
                    _toastEvent.tryEmit("✅ 唤醒词「$wakeWord」训练完成（DSCNN embedding）")
                    // 同步存到持久化存储
                    contactsRepository.saveWakeTemplate(contactId, wakeWord)
                } else {
                    Logger.w(TAG, "KWS 模板训练: 合成音频太短")
                    _toastEvent.tryEmit("合成音频太短，请重试")
                }

            } catch (e: Exception) {
                Logger.e(TAG, "KWS 训练异常: ${e.message}")
                _toastEvent.tryEmit("训练失败：${e.message}")
            } finally {
                _trainingContactId.value = null
            }
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
