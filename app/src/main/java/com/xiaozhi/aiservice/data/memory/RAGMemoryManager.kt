package com.xiaozhi.aiservice.data.memory

import android.content.Context
import com.xiaozhi.aiservice.domain.memory.LocalRAGMemory
import com.xiaozhi.aiservice.utils.Logger

/**
 * RAG 记忆管理器（全局单例）
 *
 * 提供全局访问点，让 ConnectionRepositoryImpl 和 ChatViewModel
 * 都能访问同一个记忆实例。
 */
object RAGMemoryManager {

    private const val TAG = "RAGMemoryManager"
    private var instance: LocalRAGMemory? = null

    fun getInstance(context: Context): LocalRAGMemory {
        if (instance == null) {
            instance = RAGMemoryImpl(context.applicationContext)
            Logger.i(TAG, "RAG 记忆管理器已初始化")
        }
        return instance!!
    }

    /** 获取已有实例，未初始化返回 null */
    fun getExisting(): LocalRAGMemory? = instance
}
