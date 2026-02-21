package io.legado.app.model

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.service.AutoTaskService
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx

object AutoTask {

    const val SOURCE_KEY = "auto_task"
    private const val BOOK_TASK_PREFIX = "book_update:"

    private const val KEY_RULES = "autoTaskRules"

    const val DEFAULT_CRON = "*/30 * * * *"

    fun bookTaskId(bookUrl: String): String {
        return BOOK_TASK_PREFIX + io.legado.app.utils.MD5Utils.md5Encode16(bookUrl)
    }

    fun normalizeScript(script: String): String {
        val trimmed = script.trim()
        return when {
            trimmed.startsWith("@js:", true) -> trimmed.substring(4).trim()
            trimmed.startsWith("<js>", true) && trimmed.contains("</") ->
                trimmed.substring(4, trimmed.lastIndexOf("<")).trim()
            else -> trimmed
        }
    }

    fun start(context: Context) {
        AutoTaskService.start(context)
    }

    fun stop(context: Context) {
        AutoTaskService.stop(context)
    }

    fun refreshSchedule(context: Context = appCtx) {
        if (!context.getPrefBoolean(PreferKey.autoTaskService)) return
        AutoTaskService.refresh(context)
    }

    fun buildSource(task: AutoTaskRule): BookSource {
        return BookSource(
            bookSourceUrl = "${SOURCE_KEY}:${task.id}",
            bookSourceName = task.name
        ).apply {
            jsLib = task.jsLib
            header = task.header
            concurrentRate = task.concurrentRate
            enabledCookieJar = task.enabledCookieJar
            loginUrl = task.loginUrl
            loginUi = task.loginUi
            loginCheckJs = task.loginCheckJs
        }
    }

    @Synchronized
    fun getRules(): MutableList<AutoTaskRule> {
        val json = CacheManager.get(KEY_RULES) ?: return mutableListOf()
        return GSON.fromJsonArray<AutoTaskRule>(json).getOrNull()?.toMutableList()
            ?: mutableListOf()
    }

    @Synchronized
    fun saveRules(list: List<AutoTaskRule>, refresh: Boolean = true) {
        CacheManager.put(KEY_RULES, GSON.toJson(list))
        if (refresh) {
            refreshSchedule()
        }
    }

    @Synchronized
    fun upsert(rule: AutoTaskRule) {
        val list = getRules()
        val index = list.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            list[index] = rule
        } else {
            list.add(rule)
        }
        saveRules(list)
    }

    @Synchronized
    fun delete(vararg ids: String) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        val list = getRules().filterNot { idSet.contains(it.id) }
        saveRules(list)
    }

    @Synchronized
    fun update(id: String, updater: (AutoTaskRule) -> AutoTaskRule): AutoTaskRule? {
        val list = getRules()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = updater(list[index])
        list[index] = updated
        saveRules(list, refresh = false)
        return updated
    }
}
