package com.example.oreshik.data

import android.util.Base64

/**
 * Local deterministic embedding engine for vector memory.
 *
 * Produces a normalized embedding for arbitrary text using a hashed
 * bag-of-words + character-trigram representation with sublinear TF scaling
 * and an IDF-like rarity weighting across the local corpus at search time.
 *
 * Why hashed (not a remote embedding model)?
 * - Works fully offline and instantly on-device (no provider dependency).
 * - Deterministic: the same text always produces the same vector, so stored
 *   vectors remain comparable across app restarts.
 * - Cosine similarity over trigram hashes captures lexical/semantic overlap
 *   ("prefers python" vs "user likes python scripts") well enough for
 *   memory recall ranking, which is all this feature needs.
 */
object EmbeddingEngine {
    const val DIM = 256

    /** Deterministic 32-bit hash (FNV-1a) for feature keys. */
    private fun fnv1a(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h
    }

    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "else", "when",
        "at", "by", "for", "with", "about", "against", "between", "into",
        "through", "during", "before", "after", "above", "below", "to", "from",
        "up", "down", "in", "out", "on", "off", "over", "under", "again",
        "further", "once", "here", "there", "all", "any", "both", "each",
        "few", "more", "most", "other", "some", "such", "no", "nor", "not",
        "only", "own", "same", "so", "than", "too", "very", "can", "will",
        "just", "is", "are", "was", "were", "be", "been", "being", "have",
        "has", "had", "do", "does", "did", "of", "it", "its", "as", "that",
        "this", "these", "those", "i", "you", "he", "she", "we", "they",
        "me", "my", "your", "his", "her", "our", "their", "am",
    )

    /** Tokenizes text into lowercase word tokens + character trigrams. */
    private fun features(text: String): List<String> {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { token -> (token.length >= 2) && (token !in stopWords) }
        val out = mutableListOf<String>()
        out += words
        // Character trigrams give fuzzy/typo tolerance and sub-word matching.
        words.forEach { w ->
            if (w.length >= 3) {
                for (i in 0..(w.length - 3)) out += w.substring(i, i + 3)
            }
        }
        return out
    }

    /**
     * Embeds text into a fixed-size L2-normalized vector.
     * Sublinear TF (1 + ln(tf)) dampens repeated words.
     */
    fun embed(text: String): FloatArray {
        val vec = FloatArray(DIM)
        val feats = features(text)
        if (feats.isEmpty()) return vec
        val tf = HashMap<String, Int>()
        feats.forEach { tf[it] = (tf[it] ?: 0) + 1 }
        for ((feat, count) in tf) {
            val h = fnv1a(feat)
            val idx = (h and 0x7fffffff) % DIM
            // Signed hashing reduces collision bias.
            val sign = if (h < 0) -1f else 1f
            val weight = sign * (1f + kotlin.math.ln(count.toFloat()))
            vec[idx] += weight
        }
        // L2 normalize
        var norm = 0f
        vec.forEach { norm += it * it }
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    /** Cosine similarity between two embeddings. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // vectors are pre-normalized
    }

    /** Serializes an embedding to a compact Base64 string for Room storage. */
    fun encode(vec: FloatArray): String {
        val bytes = ByteArray(vec.size * 4)
        for (i in vec.indices) {
            val bits = java.lang.Float.floatToIntBits(vec[i])
            bytes[i * 4] = (bits ushr 24).toByte()
            bytes[(i * 4) + 1] = (bits ushr 16).toByte()
            bytes[(i * 4) + 2] = (bits ushr 8).toByte()
            bytes[(i * 4) + 3] = bits.toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Deserializes a Base64 embedding string. Returns null on failure. */
    fun decode(raw: String?): FloatArray? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val vec = FloatArray(bytes.size / 4)
            for (i in vec.indices) {
                val bits = ((bytes[i * 4].toInt() and 0xff) shl 24) or
                        ((bytes[(i * 4) + 1].toInt() and 0xff) shl 16) or
                        ((bytes[(i * 4) + 2].toInt() and 0xff) shl 8) or
                        ((bytes[(i * 4) + 3].toInt()) and 0xff)
                vec[i] = java.lang.Float.intBitsToFloat(bits)
            }
            vec
        }.getOrNull()
    }
}