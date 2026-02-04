package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.model.AutoTaskProtocol
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startService
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.CronSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTaskService : BaseService() {

    companion object {
        const val EXTRA_TASK_ID = "autoTaskId"

        var isRun = false
            private set

        fun start(context: android.content.Context) {
            context.startService<AutoTaskService> {
                action = IntentAction.start
            }
        }

        fun stop(context: android.content.Context) {
            context.startService<AutoTaskService> {
                action = IntentAction.stop
            }
        }
    }

    private var taskJob: Job? = null
    private var notificationContent = appCtx.getString(R.string.service_starting)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val maxLogLength = 4000
    private val taskLock = Mutex()
    private val refreshChannel = Channel<Unit>(Channel.CONFLATED)

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.auto_task_service))
            .setContentText(notificationContent)
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<AutoTaskService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> startLoop()
            IntentAction.stop -> {
                putPrefBoolean(PreferKey.autoTaskService, false)
                stopSelf()
            }
            IntentAction.runOnce -> runOnce(intent)
            IntentAction.refreshSchedule -> refreshSchedule()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isRun = false
        taskJob?.cancel()
        super.onDestroy()
        notificationManager.cancel(NotificationId.AutoTaskService)
    }

    private fun startLoop() {
        if (taskJob?.isActive == true) return
        isRun = true
        notificationContent = getString(R.string.auto_task_running_state)
        upNotification()
        taskJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val rules = AutoTask.getRules()
                if (rules.isEmpty()) {
                    notificationContent = getString(R.string.auto_task_no_task)
                    upNotification()
                    stopSelf()
                    return@launch
                }
                val enabled = rules.filter { it.enable }
                if (enabled.isEmpty()) {
                    notificationContent = getString(R.string.auto_task_no_enabled)
                    upNotification()
                    stopSelf()
                    return@launch
                }
                val now = System.currentTimeMillis()
                var nextDelay = 60_000L
                for (task in enabled) {
                    val cron = task.cron?.trim().orEmpty()
                    if (cron.isBlank()) {
                        updateCronError(task, getString(R.string.auto_task_cron_invalid))
                        nextDelay = minOf(nextDelay, 60_000L)
                        continue
                    }
                    val schedule = CronSchedule.parse(cron)
                    if (schedule == null) {
                        updateCronError(task, getString(R.string.auto_task_cron_invalid))
                        nextDelay = minOf(nextDelay, 60_000L)
                        continue
                    }
                    val baseTime = if (task.lastRunAt > 0L) task.lastRunAt else now - 60_000L
                    val nextRun = schedule.nextTimeAfter(baseTime)
                    if (nextRun == null) {
                        updateCronError(task, getString(R.string.auto_task_cron_invalid))
                        nextDelay = minOf(nextDelay, 60_000L)
                        continue
                    }
                    if (nextRun <= now) {
                        runTask(task)
                        val afterRun = schedule.nextTimeAfter(System.currentTimeMillis())
                        if (afterRun != null) {
                            nextDelay = minOf(nextDelay, afterRun - System.currentTimeMillis())
                        }
                    } else {
                        nextDelay = minOf(nextDelay, nextRun - now)
                    }
                }
                val waitMs = nextDelay.coerceAtLeast(1000L)
                val refreshed = withTimeoutOrNull(waitMs) { refreshChannel.receive() }
                if (refreshed != null) continue
            }
        }
    }

    private fun refreshSchedule() {
        if (!isRun) {
            startLoop()
            return
        }
        refreshChannel.trySend(Unit)
    }

    private fun runOnce(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (taskId.isBlank()) {
            stopSelf()
            return
        }
        val keepAlive = isRun
        lifecycleScope.launch(Dispatchers.IO) {
            val task = AutoTask.getRules().firstOrNull { it.id == taskId }
            if (task == null) {
                notificationContent = getString(R.string.auto_task_no_task)
                upNotification()
                if (!keepAlive) stopSelf()
                return@launch
            }
            runTask(task)
            if (!keepAlive) stopSelf()
        }
    }

    private suspend fun runTask(task: AutoTaskRule) {
        taskLock.withLock {
            val script = normalizeScript(task.script)
            if (script.isBlank()) {
                val now = System.currentTimeMillis()
                AutoTask.update(task.id) {
                    it.copy(
                        lastRunAt = now,
                        lastError = getString(R.string.auto_task_script_empty)
                    )
                }
                return
            }
            val source = AutoTask.buildSource(task)
            notificationContent = getString(R.string.auto_task_running, task.name)
            upNotification()
            val startAt = System.currentTimeMillis()
            runCatching {
                source.evalJS(script)
            }.onSuccess { result ->
                try {
                    val cost = System.currentTimeMillis() - startAt
                    val logLines = mutableListOf<String>()
                    AutoTaskProtocol.handle(result, this, task.name) { msg ->
                        AppLog.put("AutoTask[${task.id}] ${task.name}: $msg")
                        logLines.add(msg)
                    }
                    val detail = result?.toString()?.take(200)
                    val msg = if (detail.isNullOrBlank()) {
                        "AutoTask[${task.id}] ${task.name} done (${cost}ms)."
                    } else {
                        "AutoTask[${task.id}] ${task.name} done (${cost}ms): $detail"
                    }
                    val lastRun = System.currentTimeMillis()
                    val lastLog = buildLastLog(logLines, detail, cost, lastRun)
                    AutoTask.update(task.id) {
                        it.copy(
                            lastRunAt = lastRun,
                            lastResult = detail,
                            lastError = null,
                            lastLog = lastLog
                        )
                    }
                    notificationContent = getString(
                        R.string.auto_task_last_run,
                        timeFormat.format(Date(lastRun))
                    )
                    upNotification()
                    AppLog.put(msg)
                } catch (error: Throwable) {
                    val msg = error.localizedMessage ?: error.toString()
                    val lastLog = buildErrorLog(msg, error, System.currentTimeMillis())
                    AutoTask.update(task.id) {
                        it.copy(
                            lastRunAt = System.currentTimeMillis(),
                            lastError = msg,
                            lastLog = lastLog
                        )
                    }
                    notificationContent = getString(R.string.auto_task_failed, msg)
                    upNotification()
                    AppLog.put("AutoTask[${task.id}] ${task.name} failed: $msg", error)
                }
            }.onFailure { error ->
                val msg = error.localizedMessage ?: error.toString()
                val lastLog = buildErrorLog(msg, error, System.currentTimeMillis())
                AutoTask.update(task.id) {
                    it.copy(
                        lastRunAt = System.currentTimeMillis(),
                        lastError = msg,
                        lastLog = lastLog
                    )
                }
                notificationContent = getString(R.string.auto_task_failed, msg)
                upNotification()
                AppLog.put("AutoTask[${task.id}] ${task.name} failed: $msg", error)
            }
        }
    }

    private fun normalizeScript(script: String): String {
        return AutoTask.normalizeScript(script)
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationContent)
        notificationManager.notify(NotificationId.AutoTaskService, notificationBuilder.build())
    }

    private fun updateCronError(task: AutoTaskRule, message: String) {
        if (task.lastError == message) return
        AutoTask.update(task.id) {
            val now = System.currentTimeMillis()
            it.copy(lastError = message, lastLog = buildErrorLog(message, null, now))
        }
    }

    private fun buildLastLog(
        lines: List<String>,
        detail: String?,
        cost: Long,
        runAt: Long
    ): String {
        val time = formatLogTime(runAt)
        val sb = StringBuilder()
        sb.append("[OK] ").append(time).append('\n')
        sb.append("耗时: ").append(cost).append("ms")
        if (lines.isNotEmpty()) {
            sb.append('\n').append("动作:")
            lines.forEach { line ->
                sb.append('\n').append("- ").append(line)
            }
        }
        if (!detail.isNullOrBlank()) {
            sb.append('\n').append("返回: ").append(detail)
        }
        val text = sb.toString().ifBlank { "执行完成" }
        return if (text.length > maxLogLength) text.take(maxLogLength) else text
    }

    private fun buildErrorLog(msg: String, error: Throwable?, runAt: Long): String {
        val time = formatLogTime(runAt)
        val detail = error?.stackTraceStr.orEmpty()
        val sb = StringBuilder()
        sb.append("[FAIL] ").append(time).append('\n')
        sb.append("错误: ").append(msg)
        if (detail.isNotBlank()) {
            sb.append('\n').append("堆栈:").append('\n').append(detail)
        }
        val text = sb.toString()
        return if (text.length > maxLogLength) text.take(maxLogLength) else text
    }

    private fun formatLogTime(time: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        startForeground(NotificationId.AutoTaskService, notificationBuilder.build())
    }
}
