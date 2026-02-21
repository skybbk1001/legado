package io.legado.app.model

data class AudioCacheStateChanged(
    val bookUrl: String,
    val chapterIndex: Int,
    val cached: Boolean,
)
