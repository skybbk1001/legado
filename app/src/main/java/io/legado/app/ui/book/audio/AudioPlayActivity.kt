package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityAudioPlayBinding
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.databinding.DialogMultipleEditTextBinding
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AudioCache
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toDurationTime
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onLongClick
import java.io.File
import java.util.Locale

/**
 * 音频播放
 */
@SuppressLint("ObsoleteSdkInt")
class AudioPlayActivity :
    VMBaseActivity<ActivityAudioPlayBinding, AudioPlayViewModel>(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack,
    AudioPlay.CallBack {

    override val binding by viewBinding(ActivityAudioPlayBinding::inflate)
    override val viewModel by viewModels<AudioPlayViewModel>()
    private val timerSliderPopup by lazy { TimerSliderPopup(this) }
    private var adjustProgress = false
    private var playMode = AudioPlay.PlayMode.LIST_END_STOP
    private var pendingCacheAction: (() -> Unit)? = null

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it.first != AudioPlay.book?.durChapterIndex
                || it.second == 0
            ) {
                AudioPlay.skipTo(it.first)
            }
        }
    }
    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val audioCacheDirSelect = registerForActivityResult(HandleFileContract()) {
        val action = pendingCacheAction
        pendingCacheAction = null
        it.uri?.let { treeUri ->
            AppConfig.audioCacheTreeUri = treeUri.toString()
            toastOnUi(R.string.audio_cache_folder_selected)
            action?.invoke()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        AudioPlay.register(this)
        viewModel.titleData.observe(this) {
            binding.titleBar.title = it
        }
        viewModel.coverData.observe(this) {
            upCover(it)
        }
        viewModel.initData(intent)
        initView()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audio_play, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !AudioPlay.bookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_wake_lock)?.isChecked = AppConfig.audioPlayUseWakeLock
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> AudioPlay.book?.let {
                showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
            }

            R.id.menu_login -> AudioPlay.bookSource?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                }
            }

            R.id.menu_wake_lock -> AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
            R.id.menu_copy_audio_url -> sendToClip(AudioPlayService.url)
            R.id.menu_clear_current_audio_cache -> clearCurrentChapterCache()
            R.id.menu_edit_source -> AudioPlay.bookSource?.let {
                sourceEditResult.launch {
                    putExtra("sourceUrl", it.bookSourceUrl)
                }
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.ivPlayMode.setOnClickListener {
            AudioPlay.changePlayMode()
        }
        binding.ivAudioSkip.setOnClickListener {
            showAudioSkipConfigDialog()
        }
        binding.ivAudioCache.setOnClickListener {
            showAudioCacheRangeDialog()
        }

        observeEventSticky<AudioPlay.PlayMode>(EventBus.PLAY_MODE_CHANGED) {
            playMode = it
            updatePlayModeIcon()
        }

        binding.fabPlayStop.setOnClickListener {
            playButton()
        }
        binding.fabPlayStop.onLongClick {
            AudioPlay.stop()
        }
        binding.ivSkipNext.setOnClickListener {
            AudioPlay.next()
        }
        binding.ivSkipPrevious.setOnClickListener {
            AudioPlay.prev()
        }
        binding.playerProgress.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvDurTime.text = progress.toDurationTime()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                adjustProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                adjustProgress = false
                AudioPlay.adjustProgress(seekBar.progress)
            }
        })
        binding.ivChapter.setOnClickListener {
            AudioPlay.book?.let {
                tocActivityResult.launch(it.bookUrl)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.ivFastRewind.invisible()
            binding.ivFastForward.invisible()
        }
        binding.ivFastForward.setOnClickListener {
            AudioPlay.adjustSpeed(0.1f)
        }
        binding.ivFastRewind.setOnClickListener {
            AudioPlay.adjustSpeed(-0.1f)
        }
        binding.ivTimer.setOnClickListener {
            timerSliderPopup.showAsDropDown(it, 0, (-100).dpToPx(), Gravity.TOP)
        }
        updateAudioSkipButtonState()
        binding.llPlayMenu.applyNavigationBarPadding()
    }

    private fun updatePlayModeIcon() {
        binding.ivPlayMode.setImageResource(playMode.iconRes)
    }

    private fun upCover(path: String?) {
        BookCover.load(this, path, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl) {
            BookCover.loadBlur(this, path, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl)
                .into(binding.ivBg)
        }.into(binding.ivCover)
    }

    private fun playButton() {
        when (AudioPlay.status) {
            Status.PLAY -> AudioPlay.pause(this)
            Status.PAUSE -> AudioPlay.resume(this)
            else -> AudioPlay.loadOrUpPlayUrl()
        }
    }

    override val oldBook: Book?
        get() = AudioPlay.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isAudio) {
            viewModel.changeTo(source, book, toc)
        } else {
            AudioPlay.stop()
            lifecycleScope.launch {
                withContext(IO) {
                    AudioPlay.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    AudioPlay.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun finish() {
        val book = AudioPlay.book ?: return super.finish()

        if (AudioPlay.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    AudioPlay.book?.removeType(BookType.notShelf)
                    AudioPlay.book?.save()
                    AudioPlay.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton { viewModel.removeFromBookshelf { super.finish() } }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AudioPlay.status != Status.PLAY) {
            AudioPlay.stop()
        }
        AudioPlay.unregister(this)
    }

    @SuppressLint("SetTextI18n")
    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            AudioPlay.status = it
            if (it == Status.PLAY) {
                binding.fabPlayStop.setImageResource(R.drawable.ic_pause_24dp)
            } else {
                binding.fabPlayStop.setImageResource(R.drawable.ic_play_24dp)
            }
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
            binding.tvSubTitle.text = it
            binding.ivSkipPrevious.isEnabled = AudioPlay.durChapterIndex > 0
            binding.ivSkipNext.isEnabled =
                AudioPlay.durChapterIndex < AudioPlay.simulatedChapterSize - 1
            updateAudioSkipButtonState()
        }
        observeEventSticky<Int>(EventBus.AUDIO_SIZE) {
            binding.playerProgress.max = it
            binding.tvAllTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_PROGRESS) {
            if (!adjustProgress) binding.playerProgress.progress = it
            binding.tvDurTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_BUFFER_PROGRESS) {
            binding.playerProgress.secondaryProgress = it

        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) {
            binding.tvSpeed.text = String.format(Locale.ROOT, "%.1fX", it)
            binding.tvSpeed.visible()
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) {
            binding.tvTimer.text = "${it}m"
            binding.tvTimer.visible(it > 0)
        }
    }

    override fun upLoading(loading: Boolean) {
        runOnUiThread {
            binding.progressLoading.visible(loading)
        }
    }

    private fun showAudioSkipConfigDialog() {
        val book = AudioPlay.book ?: return
        alert(titleResource = R.string.audio_skip_config) {
            val alertBinding = DialogMultipleEditTextBinding.inflate(layoutInflater).apply {
                layout1.hint = getString(R.string.audio_skip_intro_seconds)
                edit1.setText((book.getAudioIntroMs() / 1000).toString())
                layout2.hint = getString(R.string.audio_skip_outro_seconds)
                layout2.visible()
                edit2.setText((book.getAudioOutroMs() / 1000).toString())
            }
            customView { alertBinding.root }
            okButton {
                val introMs = parseSecondsToMs(
                    alertBinding.edit1.text?.toString(),
                    book.getAudioIntroMs()
                )
                val outroMs = parseSecondsToMs(
                    alertBinding.edit2.text?.toString(),
                    book.getAudioOutroMs()
                )
                saveBookAudioSkipConfig(book, introMs, outroMs)
            }
            neutralButton(R.string.general) {
                val introMs = parseSecondsToMs(
                    alertBinding.edit1.text?.toString(),
                    AppConfig.audioSkipIntroMs
                )
                val outroMs = parseSecondsToMs(
                    alertBinding.edit2.text?.toString(),
                    AppConfig.audioSkipOutroMs
                )
                saveGlobalAudioSkipConfig(introMs, outroMs)
            }
            cancelButton()
        }
    }

    private fun saveBookAudioSkipConfig(book: Book, introMs: Int, outroMs: Int) {
        lifecycleScope.launch(IO) {
            book.setAudioIntroMs(introMs)
            book.setAudioOutroMs(outroMs)
            book.setAudioSkipEnabled(introMs > 0 || outroMs > 0)
            book.save()
            withContext(Main) {
                updateAudioSkipButtonState()
                toastOnUi(R.string.audio_skip_saved_for_book)
            }
        }
    }

    private fun saveGlobalAudioSkipConfig(introMs: Int, outroMs: Int) {
        AppConfig.audioSkipIntroMs = introMs
        AppConfig.audioSkipOutroMs = outroMs
        AppConfig.audioSkipEnabled = introMs > 0 || outroMs > 0
        updateAudioSkipButtonState()
        toastOnUi(R.string.audio_skip_saved_as_global)
    }

    private fun parseSecondsToMs(value: String?, defaultMs: Int): Int {
        val sec = value?.trim()?.toLongOrNull() ?: return defaultMs
        return (sec.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE / 1000L) * 1000L).toInt()
    }

    private fun showAudioCacheRangeDialog() {
        val book = AudioPlay.book ?: return
        val chapterSize = AudioPlay.simulatedChapterSize
        if (chapterSize <= 0) {
            toastOnUi(R.string.no_chapter)
            return
        }
        alert(titleResource = R.string.audio_cache_notification_title) {
            val alertBinding = DialogDownloadChoiceBinding.inflate(layoutInflater).apply {
                editStart.setText((AudioPlay.durChapterIndex + 1).toString())
                editEnd.setText(chapterSize.toString())
            }
            customView { alertBinding.root }
            okButton {
                val start = parseChapterOrder(
                    value = alertBinding.editStart.text?.toString(),
                    defaultValue = AudioPlay.durChapterIndex + 1,
                    maxChapter = chapterSize
                )
                val end = parseChapterOrder(
                    value = alertBinding.editEnd.text?.toString(),
                    defaultValue = chapterSize,
                    maxChapter = chapterSize
                )
                if (start > end) {
                    toastOnUi(R.string.error_scope_input)
                    return@okButton
                }
                ensureAudioCacheDir {
                    AudioCache.cacheRange(this@AudioPlayActivity, book.bookUrl, start - 1, end - 1)
                    toastOnUi(R.string.audio_cache_start_range)
                }
            }
            cancelButton()
        }
    }

    private fun parseChapterOrder(value: String?, defaultValue: Int, maxChapter: Int): Int {
        val max = maxChapter.coerceAtLeast(1)
        val parsed = value?.trim()?.toIntOrNull() ?: defaultValue
        return parsed.coerceIn(1, max)
    }

    private fun ensureAudioCacheDir(onReady: () -> Unit) {
        if (AudioCache.hasCacheDirConfigured() && AudioCache.isCacheDirAvailable()) {
            onReady()
            return
        }
        if (AudioCache.hasCacheDirConfigured()) {
            toastOnUi(R.string.audio_cache_folder_invalid)
        } else {
            toastOnUi(R.string.audio_cache_folder_not_set)
        }
        pendingCacheAction = onReady
        audioCacheDirSelect.launch {
            title = getString(R.string.audio_cache_select_folder)
            mode = HandleFileContract.DIR_SYS
        }
    }

    private fun updateAudioSkipButtonState() {
        val enabled = AudioPlay.book?.getAudioSkipEnabled() == true
        binding.ivAudioSkip.alpha = if (enabled) 1f else 0.55f
        val introSeconds = ((AudioPlay.book?.getAudioIntroMs() ?: 0) / 1000).toString()
        val outroSeconds = ((AudioPlay.book?.getAudioOutroMs() ?: 0) / 1000).toString()
        binding.ivAudioSkip.contentDescription =
            getString(R.string.audio_skip_config_summary, introSeconds, outroSeconds)
    }

    private fun clearCurrentChapterCache() {
        val book = AudioPlay.book ?: return
        val chapter = AudioPlay.durChapter
        val chapterIndex = AudioPlay.durChapterIndex
        val source = AudioPlay.bookSource
        AudioPlay.skipCacheOnce(book.bookUrl, chapterIndex)
        AudioPlay.clearChapterPlayUrlPreload(book.bookUrl, chapterIndex)
        lifecycleScope.launch(IO) {
            val fileCacheRemoved = AudioCache.removeCachedChapter(book.bookUrl, chapterIndex)
            var playerCacheRemoved = false
            val candidateUrls = linkedSetOf<String>()
            collectPlayerCacheCandidate(candidateUrls, AudioPlayService.url)
            collectPlayerCacheCandidate(candidateUrls, AudioPlay.durPlayUrl)
            if (candidateUrls.isEmpty() && source != null && chapter != null && !chapter.isVolume) {
                kotlin.runCatching {
                    WebBook.getContentAwait(source, book, chapter, needSave = false)
                }.getOrNull()?.let {
                    collectPlayerCacheCandidate(candidateUrls, it)
                }
            }
            for (rawUrl in candidateUrls) {
                playerCacheRemoved = ExoPlayerHelper.clearCacheByPlaybackUrl(rawUrl) || playerCacheRemoved
                val resolvedUrl = kotlin.runCatching {
                    AnalyzeUrl(
                        mUrl = rawUrl,
                        source = source,
                        ruleData = book,
                        chapter = chapter
                    ).url
                }.getOrNull()
                if (!resolvedUrl.isNullOrBlank()) {
                    playerCacheRemoved =
                        ExoPlayerHelper.clearCacheByPlaybackUrl(resolvedUrl) || playerCacheRemoved
                }
            }
            withContext(Main) {
                AudioPlay.durPlayUrl = ""
                if (fileCacheRemoved || playerCacheRemoved) {
                    toastOnUi(R.string.audio_cache_current_chapter_cleared)
                } else {
                    toastOnUi(R.string.audio_cache_current_chapter_not_found)
                }
            }
        }
    }

    private fun collectPlayerCacheCandidate(target: MutableSet<String>, rawUrl: String?) {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty()) return
        if (isLikelyLocalUrl(value)) return
        target.add(value)
    }

    private fun isLikelyLocalUrl(url: String): Boolean {
        return url.startsWith("content://", true)
                || url.startsWith("file:", true)
                || url.startsWith("/", false)
                || File(url).exists()
    }

}
