package io.legado.app.help.audio

import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import io.legado.app.utils.list
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.util.Locale

object AudioCacheManager {

    private const val USER_CACHE_FOLDER = "LegadoAudioCache"

    fun hasCacheDirConfigured(): Boolean {
        return !AppConfig.audioCacheTreeUri.isNullOrBlank()
    }

    fun isCacheDirAvailable(): Boolean {
        return getUserCacheRoot() != null
    }

    fun getCachedUriString(bookUrl: String, chapterIndex: Int): String? {
        return kotlin.runCatching {
            getCachedInUserDir(bookUrl, chapterIndex)?.uri?.toString()
        }.getOrNull()
    }

    fun listCachedChapterIndexes(bookUrl: String): Set<Int> {
        val folder = getUserBookFolder(bookUrl) ?: return emptySet()
        val cachedFiles = folder.list { !it.isDir && it.size > 0L } ?: return emptySet()
        return cachedFiles.mapNotNull { chapterIndexFromFileName(it.name) }.toSet()
    }

    fun removeCachedChapter(bookUrl: String, chapterIndex: Int): Boolean {
        val folder = getUserBookFolder(bookUrl) ?: return false
        return removeChapterCache(folder, chapterIndex) > 0
    }

    suspend fun cacheChapter(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
    ): Result<String> = kotlin.runCatching {
        if (chapter.isVolume) {
            throw IllegalStateException("分卷章节不支持缓存")
        }
        if (!hasCacheDirConfigured()) {
            throw IllegalStateException(appCtx.getString(R.string.audio_cache_folder_not_set))
        }
        val playUrl = resolvePlayUrl(bookSource, book, chapter)
        val response = AnalyzeUrl(
            playUrl,
            source = bookSource,
            ruleData = book,
            chapter = chapter,
            coroutineContext = currentCoroutineContext()
        ).getResponseAwait()
        response.use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("网络请求失败(${res.code})")
            }
            val contentType = res.header("Content-Type")
            val finalUrl = res.request.url.toString()
            val ext = detectExt(contentType, finalUrl, playUrl)
            val cacheFile = createCacheFile(book, chapter, playUrl, ext)
            val expectedSize = res.body.contentLength().takeIf { it > 0L }
            try {
                res.body.byteStream().use { input ->
                    cacheFile.openOutputStream().getOrThrow().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Throwable) {
                kotlin.runCatching { cacheFile.delete() }
                throw e
            }
            val savedFile = FileDoc.fromUri(cacheFile.uri, false)
            val savedSize = savedFile.size
            if (savedSize <= 0L) {
                savedFile.delete()
                throw IllegalStateException("音频文件为空")
            }
            if (expectedSize != null && savedSize != expectedSize) {
                savedFile.delete()
                throw IllegalStateException("音频文件不完整")
            }
            savedFile.uri.toString()
        }
    }.onFailure {
        AppLog.put("缓存音频失败 ${book.name}-${chapter.title}\n${it.localizedMessage}", it)
    }

    private suspend fun resolvePlayUrl(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
    ): String {
        val content = WebBook.getContentAwait(bookSource, book, chapter, needSave = false)
        if (content.isBlank()) {
            throw IllegalStateException("播放链接为空")
        }
        return content
    }

    private fun getCachedInUserDir(bookUrl: String, chapterIndex: Int): FileDoc? {
        val bookFolder = getUserBookFolder(bookUrl) ?: return null
        val prefix = chapterPrefix(chapterIndex)
        return bookFolder.list { !it.isDir && it.name.startsWith(prefix) && it.size > 0L }
            ?.firstOrNull()
    }

    private fun getUserBookFolder(bookUrl: String): FileDoc? {
        val root = getUserCacheRoot() ?: return null
        val cacheRoot =
            root.list { it.isDir && it.name == USER_CACHE_FOLDER }?.firstOrNull() ?: return null
        val bookFolderName = "book_${MD5Utils.md5Encode16(bookUrl)}"
        return cacheRoot.list { it.isDir && it.name == bookFolderName }?.firstOrNull()
    }

    private fun getUserCacheRoot(): FileDoc? {
        val treeUri = AppConfig.audioCacheTreeUri ?: return null
        return kotlin.runCatching {
            FileDoc.fromDir(treeUri.toUri()).takeIf { it.exists() }
        }.getOrNull()
    }

    private fun requireUserBookFolder(bookUrl: String): FileDoc {
        val root = getUserCacheRoot()
            ?: throw IllegalStateException(appCtx.getString(R.string.audio_cache_folder_invalid))
        val bookFolderName = "book_${MD5Utils.md5Encode16(bookUrl)}"
        return root.createFolderIfNotExist(USER_CACHE_FOLDER, bookFolderName)
    }

    private fun createCacheFile(
        book: Book,
        chapter: BookChapter,
        playUrl: String,
        ext: String,
    ): FileDoc {
        val folder = requireUserBookFolder(book.bookUrl)
        removeChapterCache(folder, chapter.index)
        val title = chapter.title.normalizeFileName().trim('_').ifBlank { "chapter" }.take(40)
        val fileName = chapterPrefix(chapter.index) + title + "_" + MD5Utils.md5Encode16(playUrl) + ".$ext"
        return folder.createFileIfNotExist(fileName)
    }

    private fun removeChapterCache(folder: FileDoc, chapterIndex: Int): Int {
        val prefix = chapterPrefix(chapterIndex)
        val targets = folder.list { !it.isDir && it.name.startsWith(prefix) } ?: return 0
        targets.forEach {
            it.delete()
        }
        return targets.size
    }

    private fun chapterPrefix(chapterIndex: Int): String {
        return String.format(Locale.ROOT, "%05d_", chapterIndex)
    }

    private fun chapterIndexFromFileName(fileName: String): Int? {
        val prefix = fileName.substringBefore('_', "")
        if (prefix.isBlank() || !prefix.all { it.isDigit() }) return null
        return prefix.toIntOrNull()
    }

    private fun detectExt(contentType: String?, finalUrl: String, playUrl: String): String {
        val ct = contentType?.substringBefore(";")?.lowercase(Locale.ROOT)
        if (ct?.contains("mpegurl") == true || finalUrl.endsWith(".m3u8", true)) {
            throw IllegalStateException(appCtx.getString(R.string.audio_cache_skip_hls))
        }
        when {
            ct.isNullOrBlank() -> Unit
            "mpeg" in ct || "mp3" in ct -> return "mp3"
            "m4a" in ct || "mp4" in ct -> return "m4a"
            "aac" in ct -> return "aac"
            "ogg" in ct -> return "ogg"
            "wav" in ct || "x-wav" in ct -> return "wav"
            "flac" in ct -> return "flac"
            "webm" in ct -> return "webm"
        }
        val extByFinal = extFromUrl(finalUrl)
        if (extByFinal != null) return extByFinal
        val extByPlay = extFromUrl(playUrl.substringBefore(","))
        return extByPlay ?: "audio"
    }

    private fun extFromUrl(url: String): String? {
        val noQuery = url.substringBefore("?").substringBefore("#")
        val ext = noQuery.substringAfterLast(".", "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() } ?: return null
        return ext.takeIf { it.length in 2..6 && it.all { c -> c.isLetterOrDigit() } }
    }
}
