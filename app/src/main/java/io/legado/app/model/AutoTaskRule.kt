package io.legado.app.model

import java.util.UUID

data class AutoTaskRule(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var enable: Boolean = true,
    var cron: String? = AutoTask.DEFAULT_CRON,
    var loginUrl: String? = null,
    var loginUi: String? = null,
    var loginCheckJs: String? = null,
    var comment: String? = null,
    var script: String = "",
    var header: String? = null,
    var jsLib: String? = null,
    var concurrentRate: String? = null,
    var enabledCookieJar: Boolean = true,
    var lastRunAt: Long = 0L,
    var lastResult: String? = null,
    var lastError: String? = null
)
