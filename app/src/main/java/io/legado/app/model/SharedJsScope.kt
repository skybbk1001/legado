package io.legado.app.model

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.constant.AppLog
import io.legado.app.model.Debug
import kotlinx.coroutines.runBlocking
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import splitties.init.appCtx
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext

object SharedJsScope {

    private val cacheFolder = File(appCtx.cacheDir, "shareJs")
    private val aCache = ACache.get(cacheFolder)

    private val scopeMap = LruCache<String, WeakReference<Scriptable>>(16)
    private const val CRYPTO_JS_ASSET = "scripts/cryptojs.min.js"
    @Volatile
    private var cryptoJsText: String? = null
    @Volatile
    private var cryptoScope: WeakReference<Scriptable>? = null
    private val cryptoLock = Any()
    private const val CRYPTO_JS_ERROR_KEY = "cryptojs_load_error"

    private fun loadCryptoJs(): String? {
        val cached = cryptoJsText
        if (cached != null) return cached
        return try {
            val text = appCtx.assets.open(CRYPTO_JS_ASSET).bufferedReader().use { it.readText() }
            cryptoJsText = text
            text
        } catch (e: Throwable) {
            val msg = "加载CryptoJS失败: ${e.message}"
            aCache.put(CRYPTO_JS_ERROR_KEY, msg)
            Debug.log(msg)
            AppLog.putDebug(msg)
            null
        }
    }

    fun getCryptoScope(coroutineContext: CoroutineContext?): Scriptable? {
        val cached = cryptoScope?.get()
        if (cached != null) return cached
        synchronized(cryptoLock) {
            val second = cryptoScope?.get()
            if (second != null) return second
            val js = loadCryptoJs() ?: return null
            val scope = RhinoScriptEngine.run {
                getRuntimeScope(ScriptBindings())
            }
            RhinoScriptEngine.eval(js, scope, coroutineContext)
            if (scope is ScriptableObject) {
                scope.sealObject()
            }
            cryptoScope = WeakReference(scope)
            return scope
        }
    }

    fun getScope(jsLib: String?, coroutineContext: CoroutineContext?): Scriptable? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        var scope = scopeMap[key]?.get()
        if (scope == null) {
            scope = RhinoScriptEngine.run {
                getRuntimeScope(ScriptBindings())
            }
            loadCryptoJs()?.let { js ->
                RhinoScriptEngine.eval(js, scope, coroutineContext)
            }
            if (jsLib.isJsonObject()) {
                val jsMap: Map<String, String> = GSON.fromJson(
                    jsLib,
                    TypeToken.getParameterized(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    ).type
                )
                jsMap.values.forEach { value ->
                    if (value.isAbsUrl()) {
                        val fileName = MD5Utils.md5Encode(value)
                        var js = aCache.getAsString(fileName)
                        if (js == null) {
                            js = runBlocking {
                                okHttpClient.newCallStrResponse {
                                    url(value)
                                }.body
                            }
                            if (js != null) {
                                aCache.put(fileName, js)
                            } else {
                                throw NoStackTraceException("下载jsLib-${value}失败")
                            }
                        }
                        RhinoScriptEngine.eval(js, scope, coroutineContext)
                    }
                }
            } else {
                RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
            }
            if (scope is ScriptableObject) {
                scope.sealObject()
            }
            scopeMap.put(key, WeakReference(scope))
        }
        return scope
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        scopeMap.remove(key)
    }

}
