package com.tyranor.next.core.translation.service

import java.text.Normalizer
import kotlin.math.abs

/** Checks an AI result's echoed source against the source assigned to the same stable entry ID. */
internal object TranslationSourceEchoAlignment {
    const val SIMILARITY_THRESHOLD = 0.75

    fun matches(source: String, echo: String): Boolean {
        val normalizedSource = normalize(source)
        val normalizedEcho = normalize(echo)
        if (normalizedSource.isEmpty() || normalizedEcho.isEmpty()) return false
        if (normalizedSource == normalizedEcho) return true

        val longestLength = maxOf(normalizedSource.length, normalizedEcho.length)
        if (longestLength > MAX_COMPARISON_LENGTH) return false
        val maxDistance = ((1.0 - SIMILARITY_THRESHOLD) * longestLength).toInt()
        if (abs(normalizedSource.length - normalizedEcho.length) > maxDistance) return false

        return boundedLevenshteinDistance(normalizedSource, normalizedEcho, maxDistance) <= maxDistance
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase()
        .replace(NON_LETTER_OR_NUMBER, "")

    /** Banded two-row Levenshtein: only distances within the accepted threshold are evaluated. */
    private fun boundedLevenshteinDistance(left: String, right: String, limit: Int): Int {
        val unreachable = limit + 1
        var previous = IntArray(right.length + 1) { column -> if (column <= limit) column else unreachable }
        var current = IntArray(right.length + 1)

        for (row in 1..left.length) {
            val start = maxOf(1, row - limit)
            val end = minOf(right.length, row + limit)
            if (start > 1) current[start - 1] = unreachable
            current[0] = if (row <= limit) row else unreachable

            for (column in start..end) {
                val substitutionCost = if (left[row - 1] == right[column - 1]) 0 else 1
                current[column] = minOf(
                    previous[column] + 1,
                    current[column - 1] + 1,
                    previous[column - 1] + substitutionCost,
                ).coerceAtMost(unreachable)
            }
            if (end < right.length) current[end + 1] = unreachable
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private val NON_LETTER_OR_NUMBER = Regex("[^\\p{L}\\p{N}]+")
    private const val MAX_COMPARISON_LENGTH = 16_384
}
