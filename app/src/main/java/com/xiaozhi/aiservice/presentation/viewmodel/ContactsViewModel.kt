package com.xiaozhi.aiservice.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.data.audio.TtsWakeTrainer
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

    private val _recordingTemplateId = MutableStateFlow<String?>(null)
    val recordingTemplateId: StateFlow<String?> = _recordingTemplateId

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

    fun recordWakeTemplate(contactId: String) {
        if (_recordingTemplateId.value != null) return
        _recordingTemplateId.value = contactId
        viewModelScope.launch {
            try {
                Logger.i(TAG, "录制唤醒模板: contact=$contactId")
                val result = withContext(Dispatchers.IO) {
                    val sampleRate = 16000
                    val durationSec = 3
                    val bufferSize = sampleRate * durationSec * 2
                    val minBuffer = android.media.AudioRecord.getMinBufferSize(
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT
                    )
                    val bufSize = maxOf(bufferSize, minBuffer * 2)
                    val audioRecord = android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                        return@withContext null
                    }
                    audioRecord.startRecording()
                    val pcmBuffer = ByteArray(bufferSize)
                    var totalRead = 0
                    while (totalRead < bufferSize) {
                        val read = audioRecord.read(pcmBuffer, totalRead, bufferSize - totalRead)
                        if (read > 0) totalRead += read
                        if (read < 0) break
                    }
                    audioRecord.stop()
                    audioRecord.release()
                    if (totalRead < 1600) return@withContext null
                    val speechSegment = extractSpeechSegment(pcmBuffer, totalRead)
                    if (speechSegment.size < 1600) return@withContext null
                    Logger.i(TAG, "录制唤醒模板: ${totalRead} → ${speechSegment.size} bytes")
                    speechSegment
                }
                if (result != null) {
                    contactsRepository.saveWakeTemplateFromPcm(contactId, result)
                    Logger.i(TAG, "录制唤醒模板: 保存成功")
                } else {
                    Logger.w(TAG, "录制唤醒模板: 录音太短或未检测到有效语音")
                    _toastEvent.tryEmit("未检测到有效语音，请对着麦克风说出唤醒词")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "录制唤醒模板失败: ${e.message}")
                _toastEvent.tryEmit("录音失败：${e.message}")
            } finally {
                _recordingTemplateId.value = null
            }
        }
    }

    /**
     * TTS 自动训练唤醒词模板。
     * 使用 Android TextToSpeech 引擎合成唤醒词音频，无需用户录音。
     */
    fun trainWakeTemplate(contactId: String, wakeWord: String) {
        if (_trainingContactId.value != null) return
        if (wakeWord.isBlank()) {
            _toastEvent.tryEmit("请先输入唤醒词")
            return
        }
        _trainingContactId.value = contactId
        viewModelScope.launch {
            try {
                Logger.w(TAG, "TTS 自动训练唤醒词: contact=$contactId, word=「$wakeWord」")
                _toastEvent.tryEmit("正在用 Edge TTS 在线合成唤醒词「$wakeWord」…")
                val trainer = TtsWakeTrainer()
                val base64 = trainer.train(wakeWord)
                if (base64 != null) {
                    contactsRepository.saveWakeTemplate(contactId, base64)
                    Logger.i(TAG, "TTS 自动训练模板: 保存成功")
                    _toastEvent.tryEmit("✅ 唤醒词模板训练完成（TTS 自动合成）")
                } else {
                    Logger.w(TAG, "TTS 自动训练模板: 合成失败")
                    _toastEvent.tryEmit("Edge TTS 合成失败，请检查网络连接后重试")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "TTS 自动训练异常: ${e.message}")
                _toastEvent.tryEmit("训练失败：${e.message}")
            } finally {
                _trainingContactId.value = null
            }
        }
    }

    private fun extractSpeechSegment(pcmData: ByteArray, validBytes: Int): ByteArray {
        val RMS_THRESHOLD = 500
        val FRAME_MS = 30
        val FRAME_SIZE = 16 * FRAME_MS * 2  // 960 bytes @16kHz 16bit per 30ms
        val frameCount = validBytes / FRAME_SIZE
        if (frameCount < 2) return ByteArray(0)
        val isSpeech = BooleanArray(frameCount)
        for (i in 0 until frameCount) {
            var sumSq = 0.0
            val offset = i * FRAME_SIZE
            val end = minOf(offset + FRAME_SIZE, validBytes)
            var count = 0
            var j = offset
            while (j + 1 < end) {
                val lo = pcmData[j].toInt() and 0xFF
                val hi = pcmData[j + 1].toInt() and 0xFF
                val sample = (hi shl 8 or lo).toShort().toInt()
                sumSq += (sample * sample).toDouble()
                count++
                j += 2
            }
            val rms = if (count > 0) kotlin.math.sqrt(sumSq / count) else 0.0
            isSpeech[i] = rms > RMS_THRESHOLD
        }
        var startFrame = 0
        while (startFrame < frameCount && !isSpeech[startFrame]) startFrame++
        var endFrame = frameCount - 1
        while (endFrame > startFrame && !isSpeech[endFrame]) endFrame--
        startFrame = maxOf(0, startFrame - 2)
        endFrame = minOf(frameCount - 1, endFrame + 2)
        if (startFrame >= endFrame) return ByteArray(0)
        val startByte = startFrame * FRAME_SIZE
        val endByte = minOf((endFrame + 1) * FRAME_SIZE, validBytes)
        return pcmData.copyOfRange(startByte, endByte)
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
