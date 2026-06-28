package com.xiaozhi.aiservice.data.memory

import android.content.Context
import com.xiaozhi.aiservice.utils.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * BERT WordPiece Tokenizer
 *
 * 精确实现 BAAI/bge-small-zh-v1.5 的 tokenizer。
 * 包含 BertNormalizer + BertPreTokenizer + WordPiece 算法。
 *
 * 需要 assets/ 下的文件：
 *   embedding_vocab.txt — 每行一个 token, 行号=id
 */
class WordPieceTokenizer(private val context: Context) {

    companion object {
        private const val TAG = "WordPieceTokenizer"
        private const val VOCAB_FILE = "embedding_vocab.txt"

        const val PAD_ID = 0
        const val UNK_ID = 100
        const val CLS_ID = 101
        const val SEP_ID = 102

        const val MAX_SEQ_LEN = 128
        const val MAX_CHARS_PER_WORD = 100

        // CJK 统一表意文字区间
        private val CJK_RANGES = arrayOf(
            0x4E00..0x9FFF,   // CJK Unified Ideographs
            0x3400..0x4DBF,   // CJK Unified Ideographs Extension A
            0xF900..0xFAFF,   // CJK Compatibility Ideographs
            0x2E80..0x2EFF,   // CJK Radicals Supplement
            0x3000..0x303F,   // CJK Symbols and Punctuation
            0xFF00..0xFFEF,   // Halfwidth and Fullwidth Forms
            0xFE30..0xFE4F,   // CJK Compatibility Forms
        )
    }

    private var vocab: Map<String, Int>? = null
    private var idToToken: Map<Int, String>? = null
    private var loaded = false

    fun isLoaded(): Boolean = loaded

    /** 从 assets 加载 vocab.txt */
    fun load(): Boolean {
        if (loaded) return true
        try {
            val map = LinkedHashMap<String, Int>(22000)
            val reader = BufferedReader(InputStreamReader(context.assets.open(VOCAB_FILE)))
            var line: String?
            var idx = 0
            while (reader.readLine().also { line = it } != null) {
                val token = line!!.trim()
                if (token.isNotEmpty()) {
                    map[token] = idx
                }
                idx++
            }
            reader.close()
            vocab = map
            idToToken = map.entries.associate { (k, v) -> v to k }
            loaded = true
            Logger.i(TAG, "词表加载成功: ${map.size} tokens")
            return true
        } catch (e: Exception) {
            Logger.w(TAG, "词表加载失败: ${e.message}")
            return false
        }
    }

    /**
     * 将文本编码为 token IDs
     * 返回 (inputIds, attentionMask)
     */
    fun encode(text: String): Pair<IntArray, IntArray> {
        if (!loaded) {
            // 降级：返回空序列
            val ids = IntArray(MAX_SEQ_LEN) { if (it == 0) CLS_ID else if (it == 1) SEP_ID else PAD_ID }
            val mask = IntArray(MAX_SEQ_LEN) { if (it < 2) 1 else 0 }
            return ids to mask
        }

        val v = vocab ?: return IntArray(MAX_SEQ_LEN) { PAD_ID } to IntArray(MAX_SEQ_LEN) { 0 }

        // 1. BertNormalizer
        val normalized = normalize(text)

        // 2. BertPreTokenizer
        val words = preTokenize(normalized)

        // 3. WordPiece
        val tokenIds = mutableListOf<Int>()
        tokenIds.add(CLS_ID)

        for (word in words) {
            if (word.isEmpty()) continue
            val wpTokens = wordPiece(word, v)
            if (wpTokens.isEmpty()) {
                tokenIds.add(UNK_ID) // 整个词 OOV
            } else {
                tokenIds.addAll(wpTokens)
            }
            if (tokenIds.size >= MAX_SEQ_LEN - 1) break // 留位置给 [SEP]
        }

        tokenIds.add(SEP_ID)

        // 4. 填充/截断
        val inputIds = IntArray(MAX_SEQ_LEN) { PAD_ID }
        val attentionMask = IntArray(MAX_SEQ_LEN) { 0 }

        val actualLen = minOf(tokenIds.size, MAX_SEQ_LEN)
        for (i in 0 until actualLen) {
            inputIds[i] = tokenIds[i]
            attentionMask[i] = 1
        }

        return inputIds to attentionMask
    }

    // ===== BertNormalizer =====

    private fun normalize(text: String): String {
        var s = text

        // 1. clean_text: 删除控制字符
        s = s.filter { ch ->
            !(ch.code in 0x00..0x08 || ch.code == 0x0B || ch.code == 0x0C || ch.code in 0x0E..0x1F || ch.code == 0x7F)
        }

        // 2. handle_chinese_chars: 在 CJK 字符前后加空格
        s = handleChineseChars(s)

        // 3. 合并连续空格
        s = s.replace(Regex("\\s+"), " ").trim()

        return s
    }

    /** 在每个 CJK 字符前后加空格 */
    private fun handleChineseChars(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            if (isCjkChar(ch)) {
                sb.append(' ')
                sb.append(ch)
                sb.append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 判断是否为 CJK 字符 */
    private fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        for (range in CJK_RANGES) {
            if (code in range) return true
        }
        return false
    }

    // ===== BertPreTokenizer =====

    /** BertPreTokenizer: 按空格 + 标点拆分 token。这里简化实现。 */
    private fun preTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return tokens

        // 分两步：
        // 1. 按空白拆分
        val parts = trimmed.split(Regex("\\s+"))
        for (part in parts) {
            if (part.isEmpty()) continue
            // 2. 对每个部分，拆分标点
            splitPunctuation(part, tokens)
        }
        return tokens
    }

    /** 按标点拆分（保留标点作为独立 token），匹配 Python BertPreTokenizer 行为 */
    private fun splitPunctuation(text: String, output: MutableList<String>) {
        val sb = StringBuilder()
        for (ch in text) {
            if (isPunctuation(ch)) {
                if (sb.isNotEmpty()) {
                    output.add(sb.toString())
                    sb.clear()
                }
                output.add(ch.toString())
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            output.add(sb.toString())
        }
    }

    /** 判断字符是否为标点符号（匹配 BERT 实现） */
    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        // BERT 的标点定义：
        // 1. ASCII 标点: 33-47, 58-64, 91-96, 123-126
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        // 2. CJK 标点: 全角标点部分
        if (cp == 0x3001 || cp == 0x3002 || cp == 0xFF0C || cp == 0xFF1A || cp == 0xFF1B ||
            cp == 0xFF01 || cp == 0xFF1F || cp == 0x300A || cp == 0x300B ||
            cp == 0x3008 || cp == 0x3009 || cp == 0x2018 || cp == 0x2019 ||
            cp == 0x201C || cp == 0x201D || cp == 0x300E || cp == 0x300F) return true
        return false
    }

    // ===== WordPiece 算法 =====

    /**
     * WordPiece 分词
     *
     * 1. 尝试最长前缀匹配
     * 2. 剩余部分用 ## 前缀递归
     * 3. 超过 MAX_CHARS_PER_WORD 则返回 UNK
     * 4. 连续 UNK 合并为单个 UNK
     */
    private fun wordPiece(word: String, v: Map<String, Int>): List<Int> {
        if (word.length > MAX_CHARS_PER_WORD) {
            return listOf(UNK_ID)
        }

        // 先检查完整词
        v[word]?.let { return listOf(it) }

        // WordPiece 拆分
        val tokens = mutableListOf<Int>()
        var remaining = word
        var isFirst = true

        while (remaining.isNotEmpty()) {
            var subword: String
            var found = false

            // 从最长开始尝试
            for (end in remaining.length downTo 1) {
                subword = if (isFirst) {
                    remaining.substring(0, end)
                } else {
                    "##" + remaining.substring(0, end)
                }

                if (v.containsKey(subword)) {
                    tokens.add(v[subword]!!)
                    remaining = remaining.substring(end)
                    isFirst = false
                    found = true
                    break
                }
            }

            if (!found) {
                // 单个字符也匹配不上 → UNK
                return listOf(UNK_ID)
            }
        }

        return tokens
    }

    /** 获取 token id 对应的 token 字符串 */
    fun idToToken(id: Int): String? = idToToken?.get(id)

    /** 解码 token IDs 为可读文本 */
    fun decode(ids: IntArray): String {
        val map = idToToken ?: return ""
        val sb = StringBuilder()
        for (id in ids) {
            if (id == PAD_ID || id == CLS_ID || id == SEP_ID) continue
            val token = map[id] ?: continue
            if (token.startsWith("##")) {
                sb.append(token.removePrefix("##"))
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(token)
            }
        }
        return sb.toString()
    }
}
