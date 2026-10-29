package com.example.epubreader.data

data class BookEntity(
    val id: Long,
    val title: String,
    val author: String,
    val format: String,
    val path: String,
    val cover: String?,
    val added: Long,
    val opened: Long,
    val chapter: Int,
    val progress: Float,
    val percent: Float,
    val sourceName: String,
    val size: Long,
)

data class Tag(
    val id: Long,
    val bookId: Long,
    val name: String,
    val color: Int,
    val description: String,
)

data class Annotation(
    val id: Long,
    val bookId: Long,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val note: String,
    /** Highlight colour key (yellow, green, …) or null for an underline-only tag link. */
    val color: String?,
    val created: Long,
    val tagIds: List<Long>,
)

object Palette {
    val highlightKeys = listOf("yellow", "green", "blue", "pink", "purple")

    fun highlight(key: String?): Long = when (key) {
        "yellow" -> 0xFFFFD54F
        "green" -> 0xFF81C784
        "blue" -> 0xFF64B5F6
        "pink" -> 0xFFF06292
        "purple" -> 0xFF9575CD
        else -> 0x00000000
    }

    val tagColors = listOf(
        0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA,
        0xFF00897B, 0xFFD81B60, 0xFF6D4C41, 0xFF3949AB, 0xFF7CB342,
    ).map { it.toInt() }

    fun cssColor(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)
}
