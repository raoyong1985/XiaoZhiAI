package com.xiaozhi.aiservice.domain.model

import java.util.UUID

/**
 * 笔记实体
 */
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val reminderAt: Long? = null
)
