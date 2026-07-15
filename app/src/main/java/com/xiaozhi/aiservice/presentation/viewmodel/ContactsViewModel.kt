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

    /** 训练状态文本（contactId → 状态），各联系人独立显示 */
    private val _trainingStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val trainingStatus: StateFlow<Map<String, String>> = _trainingStatus

    /**
     * 获取指定联系人的训练状态
     */
    fun getTrainingStatus(contactId: String): String = _trainingStatus.value[contactId] ?: ""

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
            _trainingStatus.value = _trainingStatus.value + (contactId to "请先输入唤醒词")
            return
        }
        _trainingContactId.value = contactId
        _trainingStatus.value = _trainingStatus.value + (contactId to "正在用 Edge TTS 合成「$wakeWord」…")
        viewModelScope.launch {
            try {
                Logger.w(TAG, "KWS 自动训练: contact=$contactId, word=「$wakeWord」")

                // 初始化 KWS 引擎
                if (kwsEngine == null || !kwsEngine!!.initialized) {
                    _trainingStatus.value = _trainingStatus.value + (contactId to "正在加载唤醒词引擎…")
                    kwsEngine = com.xiaozhi.aiservice.data.audio.KwsEngine()
                    kwsEngine!!.init(context)
                }
                if (!kwsEngine!!.initialized) {
                    Logger.e(TAG, "KWS 引擎初始化失败")
                    _trainingStatus.value = _trainingStatus.value + (contactId to "❌ KWS 引擎初始化失败（ONNX 模型加载异常）")
                    return@launch
                }

                _trainingStatus.value = _trainingStatus.value + (contactId to "正在合成唤醒词音频…")
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

                // 慢速语速
                _trainingStatus.value = _trainingStatus.value + (contactId to "正在合成多样化本…")
                val slow = edgeTts.synthesize(wakeWord)
                if (slow != null) pcmSamples.add(slow)

                val extra = edgeTts.synthesize(wakeWord)
                if (extra != null) pcmSamples.add(extra)

                if (pcmSamples.size < 2) {
                    Logger.w(TAG, "KWS 训练失败: Edge TTS 合成次数不足")
                    _trainingStatus.value = _trainingStatus.value + (contactId to "❌ Edge TTS 合成失败，请检查网络连接后重试")
                    return@launch
                }

                _trainingStatus.value = _trainingStatus.value + (contactId to "正在提取声纹特征…")
                val success = kwsEngine!!.addTemplateFromMultiplePcm(contactId, wakeWord, pcmSamples)
                if (success) {
                    Logger.i(TAG, "✅ KWS 模板训练成功: $contactId 「$wakeWord」")
                    _trainingStatus.value = _trainingStatus.value + (contactId to "✅ 唤醒词「$wakeWord」训练完成")
                    // 保存 embedding 到持久化存储
                    val td = kwsEngine!!.getTemplateData(contactId)
                    if (td != null) {
                        val buf = java.nio.ByteBuffer.allocate(2520 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        buf.asFloatBuffer().put(td.embedding)
                        val b64 = java.util.Base64.getEncoder().encodeToString(buf.array())
                        contactsRepository.saveWakeTemplate(contactId, b64)
                    }
                } else {
                    Logger.w(TAG, "KWS 模板训练: 合成音频太短")
                    _trainingStatus.value = _trainingStatus.value + (contactId to "❌ 合成音频太短，请重试")
                }

            } catch (e: Exception) {
                Logger.e(TAG, "KWS 训练异常: ${e.message}")
                _trainingStatus.value = _trainingStatus.value + (contactId to "❌ 训练失败：${e.message}")
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
