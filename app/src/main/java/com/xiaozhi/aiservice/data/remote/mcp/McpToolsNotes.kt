package com.xiaozhi.aiservice.data.remote.mcp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.note.NoteStorage
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.data.remote.mcp.ReminderReceiver
import com.xiaozhi.aiservice.domain.model.Note

fun registerNoteTools(registry: MCPToolRegistry) {
    // ──────────────── clipboard ────────────────
    registry.registerTool(
        name = "clipboard",
        description = "读取或写入系统剪贴板。读取时返回剪贴板文本内容，写入时设置新文本到剪贴板",
        inputSchema = jsonSchema(
            "action" to jsonStringProp("操作: read（读取）/ write（写入），默认 read"),
            "text" to jsonStringProp("写入模式下的文本内容"),
            required = listOf("action")
        )
    ) { args ->
        val action = (args["action"] as? String)?.lowercase() ?: "read"
        val ctx = registry.context
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: throw Exception("此设备不支持剪贴板")

        when (action) {
            "read" -> {
                val clip = clipboard.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() ?: "" else ""
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("action", "read")
                    addProperty("has_text", text.isNotEmpty())
                    addProperty("text", text)
                }
            }
            "write" -> {
                val text = (args["text"] as? String)?.takeIf { it.isNotBlank() }
                    ?: throw Exception("写入模式必须提供 text 参数")
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("xiaozhi", text))
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("action", "write")
                    addProperty("text_length", text.length)
                    addProperty("message", "已复制到剪贴板")
                }
            }
            else -> throw Exception("不支持的 action: $action，支持 read/write")
        }
    }

    // ──────────────── schedule_note_reminder (内部函数，用于笔记+闹钟联动) ────────────────
    fun scheduleNoteReminder(ctx: Context, note: Note) {
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra("note_id", note.id)
            putExtra("note_title", note.title)
            putExtra("note_content", note.content)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            ctx,
            (note.id.hashCode() and Int.MAX_VALUE) % 100000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            note.reminderAt ?: return,
            pendingIntent
        )
    }

    // ──────────────── save_note ────────────────
    registry.registerTool(
        name = "save_note",
        description = "保存一条笔记到手机本地。可以记录常用信息、备忘、提醒事项等，供之后查询。支持标签和时间提醒。当用户说「记住…」「帮我记一下」「记个笔记」「记备忘录」时调用此工具",
        inputSchema = jsonSchema(
            "title" to jsonStringProp("笔记的标题/关键词，用于后续查找，如「我的地址」「WiFi密码」"),
            "content" to jsonStringProp("笔记内容"),
            "tags" to jsonStringProp("可选的标签，用逗号分隔，如「生活,备忘」"),
            "reminder_at" to jsonNumberProp("可选，提醒时间戳（毫秒），设置了会在此时间提醒"),
            required = listOf("title", "content")
        )
    ) { args ->
        val storage = NoteStorage(registry.context)
        val title = (args["title"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("title 不能为空")
        val content = (args["content"] as? String)?.takeIf { it != null }
            ?: throw Exception("content 不能为空")
        val tags = (args["tags"] as? String)?.takeIf { it.isNotBlank() }
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val reminderAt = (args["reminder_at"] as? Number)?.toLong()

        val note = Note(
            title = title,
            content = content,
            tags = tags,
            reminderAt = reminderAt
        )
        storage.save(note)

        // 有提醒时间 → 注册系统闹钟
        if (reminderAt != null) {
            scheduleNoteReminder(registry.context, note)
        }

        val result = JsonObject().apply {
            addProperty("success", true)
            addProperty("title", title)
            addProperty("content_length", content.length)
            addProperty("tags", tags.joinToString(","))
            addProperty("created_at", note.createdAt)
            addProperty("message", "已保存笔记「${title}」${if (tags.isNotEmpty()) "（标签：${tags.joinToString("，")}）" else ""}")
            if (reminderAt != null) {
                addProperty("reminder_at", reminderAt)
                addProperty("reminder_note", "将在指定时间提醒")
            }
        }
        result
    }

    // ──────────────── read_note ────────────────
    registry.registerTool(
        name = "read_note",
        description = "读取之前保存的笔记内容。支持按标题、关键词或标签查找。当用户说「我之前记的…」「查一下笔记」「我的备忘录」时调用此工具",
        inputSchema = jsonSchema(
            "query" to jsonStringProp("搜索关键词，匹配标题或内容"),
            "tag" to jsonStringProp("可选，按标签筛选"),
            required = listOf()
        )
    ) { args ->
        val storage = NoteStorage(registry.context)
        val query = (args["query"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val notes = when {
            tag != null -> storage.getByTag(tag)
            query != null -> storage.search(query)
            else -> storage.getAll()
        }

        if (notes.isEmpty()) {
            throw Exception(if (query != null) "未找到匹配「$query」的笔记" else "还没有保存过笔记")
        }

        val results = JsonArray()
        notes.forEach { note ->
            results.add(JsonObject().apply {
                addProperty("id", note.id)
                addProperty("title", note.title)
                addProperty("content", note.content)
                addProperty("tags", note.tags.joinToString(","))
                addProperty("created_at", note.createdAt)
                addProperty("updated_at", note.updatedAt)
                if (note.reminderAt != null) {
                    addProperty("reminder_at", note.reminderAt)
                }
            })
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("count", notes.size)
            add("results", results)
            if (query != null) addProperty("query", query)
            if (tag != null) addProperty("tag", tag)
        }
    }

    // ──────────────── list_notes ────────────────
    registry.registerTool(
        name = "list_notes",
        description = "列出所有已保存的笔记概要（标题、标签、创建时间）。支持按标签筛选",
        inputSchema = jsonSchema(
            "tag" to jsonStringProp("可选，只列出特定标签的笔记"),
        )
    ) { args ->
        val storage = NoteStorage(registry.context)
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val notes = if (tag != null) storage.getByTag(tag) else storage.getAll()

        val arr = JsonArray()
        notes.forEach { note ->
            arr.add(JsonObject().apply {
                addProperty("id", note.id)
                addProperty("title", note.title)
                addProperty("content_length", note.content.length)
                addProperty("tags", note.tags.joinToString(","))
                addProperty("created_at", note.createdAt)
                if (note.reminderAt != null) {
                    addProperty("has_reminder", true)
                }
            })
        }

        JsonObject().apply {
            add("notes", arr)
            addProperty("count", arr.size())
            if (tag != null) addProperty("tag", tag)
            addProperty("sort", "按创建时间降序")
        }
    }

    // ──────────────── delete_note ────────────────
    registry.registerTool(
        name = "delete_note",
        description = "删除一条已保存的笔记。支持按标题或ID删除。当用户说「删除笔记」「删掉那条记录」时调用此工具",
        inputSchema = jsonSchema(
            "title" to jsonStringProp("要删除的笔记标题（精确匹配优先，失败则模糊匹配）"),
            "id" to jsonStringProp("要删除的笔记ID（如果知道的话）"),
            required = listOf()
        )
    ) { args ->
        val storage = NoteStorage(registry.context)
        val id = (args["id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val title = (args["title"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        if (id != null) {
            if (storage.delete(id)) {
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("message", "已删除笔记")
                }
            }
            throw Exception("未找到该笔记")
        }

        if (title != null) {
            // 先精确匹配
            val exact = storage.search(title).filter { it.title.equals(title, ignoreCase = true) }
            if (exact.size == 1) {
                storage.delete(exact[0].id)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("title", title)
                    addProperty("message", "已删除笔记「$title」")
                }
            }
            // 模糊删除
            val deleted = storage.deleteByTitle(title)
            if (deleted > 0) {
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("query", title)
                    addProperty("deleted_count", deleted)
                    addProperty("message", "已删除 $deleted 条匹配笔记")
                }
            }
            throw Exception("未找到标题包含「$title」的笔记")
        }

        throw Exception("请提供要删除的笔记 title 或 id")
    }
}
