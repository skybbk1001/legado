package io.legado.app.model

import android.content.Context
import io.legado.app.constant.EventBus
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.service.AudioCacheService
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService

object AudioCache {

    fun hasCacheDirConfigured(): Boolean {
        return AudioCacheManager.hasCacheDirConfigured()
    }

    fun isCacheDirAvailable(): Boolean {
        return AudioCacheManager.isCacheDirAvailable()
    }

    fun getCachedUriString(bookUrl: String, chapterIndex: Int): String? {
        return AudioCacheManager.getCachedUriString(bookUrl, chapterIndex)
    }

    fun listCachedChapterIndexes(bookUrl: String): Set<Int> {
        return AudioCacheManager.listCachedChapterIndexes(bookUrl)
    }

    fun removeCachedChapter(bookUrl: String, chapterIndex: Int): Boolean {
        val removed = AudioCacheManager.removeCachedChapter(bookUrl, chapterIndex)
        if (removed) {
            postEvent(
                EventBus.AUDIO_CACHE_CHANGED,
                AudioCacheStateChanged(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    cached = false
                )
            )
        }
        return removed
    }

    fun cacheRange(context: Context, bookUrl: String, start: Int, end: Int) {
        if (start !in 0..end) return
        context.startService<AudioCacheService> {
            action = AudioCacheService.ACTION_CACHE_RANGE
            putExtra("bookUrl", bookUrl)
            putExtra("start", start)
            putExtra("end", end)
        }
    }
}
