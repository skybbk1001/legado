package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.google.gson.reflect.TypeToken
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.externalCache
import okhttp3.CacheControl
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit


@Suppress("unused")
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val formatUrl = url + SPLIT_TAG + GSON.toJson(headers, mapType)
        return MediaItem.Builder().setUri(formatUrl).build()
    }

    fun clearCacheByPlaybackUrl(url: String): Boolean {
        val target = url.trim()
        if (target.isEmpty()) return false
        val pureUrl = target.substringBefore(SPLIT_TAG)
        var removed = false
        removed = clearCacheByKey(target) || removed
        if (pureUrl != target) {
            removed = clearCacheByKey(pureUrl) || removed
        }
        return removed
    }

    private fun clearCacheByKey(key: String): Boolean {
        return kotlin.runCatching {
            if (cache.getCachedSpans(key).isEmpty()) {
                false
            } else {
                cache.removeResource(key)
                true
            }
        }.getOrDefault(false)
    }

    fun createHttpExoPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10
            ).build()

        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(
                context,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            ).setDataSourceFactory(resolvingDataSource)
                .setLiveTargetOffsetMs(5000)
        ).build()
    }


    private val resolvingDataSource: ResolvingDataSource.Factory by lazy {
        ResolvingDataSource.Factory(cacheDataSourceFactory) {
            var res = it

            if (it.uri.toString().contains(SPLIT_TAG)) {
                val urls = it.uri.toString().split(SPLIT_TAG)
                val url = urls[0]
                res = res.withUri(url.toUri())
                try {
                    val headers: Map<String, String> = GSON.fromJson(urls[1], mapType)
                    okhttpDataFactory.setDefaultRequestProperties(headers)
                } catch (_: Exception) {
                }
            }

            res

        }
    }


    /**
     * 支持缓存的DataSource.Factory
     */
    private val cacheDataSourceFactory by lazy {
        //使用自定义的CacheDataSource以支持设置UA
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    /**
     * 默认数据源,本地(content/file)与网络(http/https)统一由它分发
     */
    private val defaultDataSourceFactory by lazy {
        DefaultDataSource.Factory(appCtx, okhttpDataFactory)
    }

    /**
     * Okhttp DataSource.Factory
     */
    private val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
    }

    /**
     * Exoplayer 内置的缓存
     */
    private val cache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(appCtx)
        return@lazy SimpleCache(
            //Exoplayer的缓存路径
            File(appCtx.externalCache, "exoplayer"),
            //100M的缓存
            LeastRecentlyUsedCacheEvictor((100 * 1024 * 1024).toLong()),
            //记录缓存的数据库
            databaseProvider
        )
    }

}
