package com.xiaozhi.aiservice.data.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.xiaozhi.aiservice.domain.memory.MemoryRecord
import com.xiaozhi.aiservice.domain.memory.SearchResult
import com.xiaozhi.aiservice.utils.Logger
import kotlin.math.sqrt

/**
 * SQLite 记忆存储
 *
 * 支持关键词搜索和向量余弦相似度搜索。
 * 无外部依赖，使用 Android 内置 SQLite。
 */
class MemoryDatabase(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "MemoryDatabase"
        private const val DB_NAME = "xiaozhi_rag.db"
        private const val DB_VERSION = 1

        private const val TABLE = "memory_records"
        private const val COL_ID = "id"
        private const val COL_AGENT = "agent_id"
        private const val COL_ROLE = "role"
        private const val COL_TEXT = "text"
        private const val COL_KEYWORDS = "keywords"
        private const val COL_IMPORTANCE = "importance"
        private const val COL_EMBEDDING = "embedding"
        private const val COL_CREATED = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_AGENT TEXT NOT NULL,
                $COL_ROLE TEXT NOT NULL DEFAULT '',
                $COL_TEXT TEXT NOT NULL,
                $COL_KEYWORDS TEXT DEFAULT '',
                $COL_IMPORTANCE REAL DEFAULT 0.5,
                $COL_EMBEDDING BLOB,
                $COL_CREATED INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_agent ON $TABLE($COL_AGENT)")
        Logger.i(TAG, "数据库已创建: $DB_NAME")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ── 写入 ──────────────────────────────────────────────

    fun insert(record: MemoryRecord, embedding: FloatArray? = null): Long {
        val db = writableDatabase
        val values = android.content.ContentValues().apply {
            put(COL_AGENT, record.agentId)
            put(COL_ROLE, record.role)
            put(COL_TEXT, record.text)
            put(COL_KEYWORDS, record.keywords)
            put(COL_IMPORTANCE, record.importance.toDouble())
            put(COL_CREATED, record.createdAt)
            if (embedding != null) {
                put(COL_EMBEDDING, floatArrayToBytes(embedding))
            }
        }
        return db.insert(TABLE, null, values)
    }

    // ── 关键词搜索 ────────────────────────────────────────

    fun searchByKeyword(agentId: String, query: String, topK: Int): List<MemoryRecord> {
        val db = readableDatabase
        val tokens = query
            .replace(Regex("[，。！？、；：\"\"''（）\\[\\]{}【】…—·\\s]"), " ")
            .split(" ")
            .filter { it.length >= 2 }
            .take(5)

        if (tokens.isEmpty()) return emptyList()

        // 构建 LIKE 条件
        val likeClauses = tokens.joinToString(" OR ") {
            "$COL_TEXT LIKE ? OR $COL_KEYWORDS LIKE ?"
        }
        val likeArgs = tokens.flatMap { listOf("%$it%", "%$it%") }.toTypedArray()

        val cursor = db.query(
            TABLE, null,
            "$COL_AGENT = ? AND ($likeClauses)",
            arrayOf(agentId) + likeArgs,
            null, null,
            "$COL_IMPORTANCE DESC, $COL_CREATED DESC",
            topK.toString()
        )

        val results = mutableListOf<MemoryRecord>()
        while (cursor.moveToNext()) {
            results.add(cursorToRecord(cursor))
        }
        cursor.close()
        return results
    }

    // ── 向量搜索 ──────────────────────────────────────────

    fun searchByVector(agentId: String, queryEmbedding: FloatArray, topK: Int): List<SearchResult> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE, null,
            "$COL_AGENT = ? AND $COL_EMBEDDING IS NOT NULL",
            arrayOf(agentId),
            null, null,
            "$COL_CREATED DESC"
        )

        val scored = mutableListOf<SearchResult>()
        while (cursor.moveToNext()) {
            val blob = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_EMBEDDING))
            if (blob == null) continue
            val vec = bytesToFloatArray(blob)
            if (vec.size != queryEmbedding.size) continue
            val sim = cosineSimilarity(queryEmbedding, vec)
            scored.add(SearchResult(cursorToRecord(cursor), sim))
        }
        cursor.close()

        // 按相似度排序，取 topK
        scored.sortByDescending { it.score }
        return scored.take(topK)
    }

    // ── 最近记录 ──────────────────────────────────────────

    fun getRecent(agentId: String, limit: Int): List<MemoryRecord> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE, null,
            "$COL_AGENT = ?",
            arrayOf(agentId),
            null, null,
            "$COL_CREATED DESC",
            limit.toString()
        )
        val results = mutableListOf<MemoryRecord>()
        while (cursor.moveToNext()) {
            results.add(cursorToRecord(cursor))
        }
        cursor.close()
        // 按时间正序返回（旧→新）
        results.reverse()
        return results
    }

    // ── 删除 ──────────────────────────────────────────────

    fun deleteByAgent(agentId: String) {
        writableDatabase.delete(TABLE, "$COL_AGENT = ?", arrayOf(agentId))
    }

    // ── 工具 ──────────────────────────────────────────────

    private fun cursorToRecord(c: android.database.Cursor): MemoryRecord {
        return MemoryRecord(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            agentId = c.getString(c.getColumnIndexOrThrow(COL_AGENT)),
            role = c.getString(c.getColumnIndexOrThrow(COL_ROLE)) ?: "",
            text = c.getString(c.getColumnIndexOrThrow(COL_TEXT)) ?: "",
            keywords = c.getString(c.getColumnIndexOrThrow(COL_KEYWORDS)) ?: "",
            importance = c.getFloat(c.getColumnIndexOrThrow(COL_IMPORTANCE)),
            createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED))
        )
    }

    /** FloatArray → ByteArray（4 bytes per float） */
    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(arr.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(arr)
        return buf.array()
    }

    /** ByteArray → FloatArray */
    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val arr = FloatArray(bytes.size / 4)
        buf.asFloatBuffer().get(arr)
        return arr
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-10f) 0f else dot / denom
    }
}
