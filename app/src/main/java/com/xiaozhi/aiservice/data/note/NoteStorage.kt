package com.xiaozhi.aiservice.data.note

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xiaozhi.aiservice.domain.model.Note

/**
 * 笔记存储层
 * 基于 Gson + SharedPreferences，每条笔记以 JSON 格式存储
 * 维护索引列表 (note_ids) 用于排序和遍历
 */
class NoteStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xiaozhi_notes_v2", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val notesListType = object : TypeToken<MutableList<String>>() {}.type

    /**
     * 获取所有笔记 ID 列表（按创建时间降序）
     */
    private fun getNoteIds(): MutableList<String> {
        val raw = prefs.getString(KEY_IDS, null) ?: return mutableListOf()
        return try {
            gson.fromJson(raw, notesListType) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveNoteIds(ids: List<String>) {
        prefs.edit().putString(KEY_IDS, gson.toJson(ids)).apply()
    }

    /**
     * 保存笔记。如果 id 已存在则更新，否则新建
     */
    fun save(note: Note) {
        val ids = getNoteIds()
        val json = gson.toJson(note)
        prefs.edit().putString("note_${note.id}", json).apply()

        if (!ids.contains(note.id)) {
            ids.add(0, note.id) // 最新放最前
            saveNoteIds(ids)
        }
    }

    /**
     * 按 ID 获取笔记
     */
    fun getById(id: String): Note? {
        val json = prefs.getString("note_$id", null) ?: return null
        return try {
            gson.fromJson(json, Note::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取所有笔记（按创建时间降序）
     */
    fun getAll(): List<Note> {
        return getNoteIds().mapNotNull { getById(it) }
    }

    /**
     * 按标题/关键词搜索笔记
     */
    fun search(query: String): List<Note> {
        val q = query.lowercase()
        return getAll().filter { note ->
            note.title.lowercase().contains(q) ||
            note.content.lowercase().contains(q) ||
            note.tags.any { it.lowercase().contains(q) }
        }
    }

    /**
     * 按标签筛选笔记
     */
    fun getByTag(tag: String): List<Note> {
        val t = tag.lowercase()
        return getAll().filter { note ->
            note.tags.any { it.lowercase() == t }
        }
    }

    /**
     * 删除笔记
     */
    fun delete(id: String): Boolean {
        val ids = getNoteIds()
        if (!ids.contains(id)) return false
        ids.remove(id)
        saveNoteIds(ids)
        prefs.edit().remove("note_$id").apply()
        return true
    }

    /**
     * 按标题模糊删除
     */
    fun deleteByTitle(title: String): Int {
        val ids = getNoteIds()
        val toRemove = ids.filter { id ->
            val note = getById(id)
            note != null && note.title.contains(title, ignoreCase = true)
        }
        toRemove.forEach { id ->
            ids.remove(id)
            prefs.edit().remove("note_$id").apply()
        }
        saveNoteIds(ids)
        return toRemove.size
    }

    /**
     * 笔记总数
     */
    fun count(): Int = getNoteIds().size

    companion object {
        private const val KEY_IDS = "note_ids"
    }
}
