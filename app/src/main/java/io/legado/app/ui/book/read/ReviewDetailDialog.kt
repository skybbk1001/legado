package io.legado.app.ui.book.read

import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemReviewCommentBinding
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.dialog.PhotoDialog
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import io.legado.app.utils.openUrl

class ReviewDetailDialog() : BaseDialogFragment(R.layout.dialog_recycler_view) {

    constructor(paragraphNum: Int, totalCount: Int) : this() {
        arguments = Bundle().apply {
            putInt("paragraphNum", paragraphNum)
            putInt("totalCount", totalCount)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { ReviewAdapter(requireContext()) }
    private var paragraphNum: Int = 0
    private var totalCount: Int = 0
    private var isLoading = false
    private var hasMore = true
    private var currentPage = 1
    private var nextPageUrl: String? = null
    private val mainItemIndexByKey = LinkedHashMap<String, Int>()
    private val detailItems = ArrayList<ReviewDetailItem>()
    private val expandedReplyParentKeys = HashSet<String>()
    private val uiItemDiffCallback = object : DiffUtil.ItemCallback<ReviewUiItem>() {
        override fun areItemsTheSame(oldItem: ReviewUiItem, newItem: ReviewUiItem): Boolean {
            if (oldItem.itemType != newItem.itemType) return false
            if (oldItem.itemType == TYPE_MORE) {
                return oldItem.parentKey == newItem.parentKey
            }
            val oldId = oldItem.id?.takeIf { it.isNotBlank() }
            val newId = newItem.id?.takeIf { it.isNotBlank() }
            if (oldId != null || newId != null) {
                return oldId == newId && oldItem.isReply == newItem.isReply
            }
            return oldItem.isReply == newItem.isReply &&
                    oldItem.parentKey == newItem.parentKey &&
                    oldItem.name == newItem.name &&
                    oldItem.content == newItem.content &&
                    oldItem.time == newItem.time &&
                    oldItem.avatar == newItem.avatar &&
                    oldItem.imageUrl == newItem.imageUrl &&
                    oldItem.audioUrl == newItem.audioUrl
        }

        override fun areContentsTheSame(oldItem: ReviewUiItem, newItem: ReviewUiItem): Boolean {
            return oldItem == newItem
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.16f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
        }
        setLayout(1f, 0.68f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        paragraphNum = arguments?.getInt("paragraphNum") ?: 0
        totalCount = arguments?.getInt("totalCount") ?: 0
        binding.root.setBackgroundResource(R.drawable.bg_dialog_round_top)
        binding.toolBar.setBackgroundResource(R.drawable.bg_review_toolbar)
        binding.toolBar.updateLayoutParams<ViewGroup.LayoutParams> {
            height = 42.dpToPx()
        }
        binding.toolBar.minimumHeight = 0
        binding.toolBar.setPadding(0, 0, 0, 0)
        binding.toolBar.setContentInsetsRelative(6.dpToPx(), 6.dpToPx())
        binding.toolBar.title = ""
        binding.toolBar.subtitle = null
        val oldCountView = binding.toolBar.findViewWithTag<View>("review_count_tag")
        if (oldCountView != null) {
            binding.toolBar.removeView(oldCountView)
        }
        if (totalCount > 0) {
            val countView = TextView(requireContext()).apply {
                tag = "review_count_tag"
                text = getString(R.string.review_total_count, totalCount)
                setTextColor(getCompatColor(R.color.secondaryText))
                textSize = 14f
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
            }
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                marginEnd = 8.dpToPx()
            }
            binding.toolBar.addView(countView, lp)
        }
        binding.toolBar.setNavigationIcon(R.drawable.ic_baseline_close)
        binding.toolBar.navigationIcon?.setTint(getCompatColor(R.color.secondaryText))
        binding.toolBar.setNavigationOnClickListener { dismiss() }
        val layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.setFastScrollEnabled(false)
        binding.recyclerView.setTrackVisible(false)
        binding.recyclerView.setBubbleVisible(false)
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                if (dy <= 0) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (hasMore && !isLoading && lastVisible >= adapter.itemCount - 3) {
                    loadDetailPage(paragraphNum, currentPage + 1, append = true)
                }
            }
        })
        loadDetailPage(paragraphNum, 1, append = false)
    }

    private fun loadDetailPage(paragraphNum: Int, page: Int, append: Boolean) {
        if (isLoading) return
        if (!append) {
            binding.rotateLoading.visible()
            binding.tvMsg.gone()
            adapter.setItems(emptyList())
            currentPage = 1
            hasMore = true
            nextPageUrl = null
            mainItemIndexByKey.clear()
            detailItems.clear()
            expandedReplyParentKeys.clear()
        }
        if (!hasMore) return
        isLoading = true
        Coroutine.async(lifecycleScope, IO) {
            val source = ReadBook.bookSource ?: return@async null
            val rule = source.ruleReview ?: return@async null
            if (!rule.enabled) return@async null
            val firstPageUrlRule = rule.reviewDetailUrl?.takeIf { it.isNotBlank() } ?: return@async null
            val nextPageUrlRule = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
            val effectiveNextUrl = nextPageUrl?.takeIf { it.isNotBlank() }
            if (page > 1 && effectiveNextUrl == null && nextPageUrlRule == null) return@async null
            val detailUrlRule = when {
                page > 1 && !effectiveNextUrl.isNullOrBlank() -> effectiveNextUrl
                page > 1 -> nextPageUrlRule ?: firstPageUrlRule
                else -> firstPageUrlRule
            }
            if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) {
                return@async null
            }
            val book = ReadBook.book ?: return@async null
            val chapterIndex = ReadBook.durChapterIndex
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@async null
            val rawKey = ChapterProvider.getReviewKeyById(paragraphNum)
            val paraIndex = paragraphNum.toString()
            val paraData = rawKey?.takeIf { it.isNotBlank() } ?: ""
            val analyzeUrl = AnalyzeUrl(
                detailUrlRule,
                page = page,
                extraParams = mapOf(
                    "paraIndex" to paraIndex,
                    "paraData" to paraData,
                    "page" to page.toString()
                ),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext
            )
            val body = analyzeUrl.getStrResponseAwait(useWebView = false).body ?: ""
            val result = parseReviewDetailList(
                body = body,
                rule = rule,
                nextPageRule = nextPageUrlRule,
                baseUrl = analyzeUrl.url,
                source = source,
                book = book,
                chapter = chapter,
                context = coroutineContext,
                paraIndex = paraIndex,
                paraData = paraData,
                page = page.toString()
            )
            ReviewResult(result.items, result.nextPageUrl, nextPageUrlRule != null)
        }.onSuccess(Main) { result ->
            if (!append) {
                binding.rotateLoading.gone()
            }
            val items = result?.items.orEmpty()
            val nextUrlFromRule = result?.nextPageUrl
            if (result?.hasNextPageRule == true) {
                nextPageUrl = nextUrlFromRule
                if (nextUrlFromRule.isNullOrBlank()) {
                    hasMore = false
                }
            }
            if (items.isEmpty() && !append) {
                hasMore = false
                binding.tvMsg.text = getString(R.string.content_empty)
                binding.tvMsg.visible()
                isLoading = false
                return@onSuccess
            }
            if (items.isEmpty()) {
                hasMore = false
                isLoading = false
                return@onSuccess
            }
            val mergedCount = mergeDetailItems(items)
            if (mergedCount == 0 && append) {
                hasMore = false
                isLoading = false
                return@onSuccess
            }
            currentPage = page
            renderUiItems()
            isLoading = false
        }.onError {
            isLoading = false
            if (!append) {
                binding.rotateLoading.gone()
                binding.tvMsg.text = it.localizedMessage ?: getString(R.string.content_empty)
                binding.tvMsg.visible()
            }
        }
    }

    private fun buildDetailItemKey(item: ReviewDetailItem, isReply: Boolean): String {
        val id = item.id?.trim().orEmpty()
        if (id.isNotEmpty()) {
            return (if (isReply) "r|" else "m|") + id
        }
        return buildString {
            append(if (isReply) "r" else "m")
            append('|')
            append(item.name.orEmpty())
            append('|')
            append(item.content.orEmpty())
            append('|')
            append(item.time.orEmpty())
            append('|')
            append(item.avatar.orEmpty())
            append('|')
            append(item.imageUrl.orEmpty())
            append('|')
            append(item.audioUrl.orEmpty())
        }
    }

    private fun mergeDetailItems(newItems: List<ReviewDetailItem>): Int {
        var changed = 0
        newItems.forEach { incoming ->
            val mainKey = buildDetailItemKey(incoming, isReply = false)
            val normalizedReplies = dedupeReplies(incoming.replies)
            val normalized = incoming.copy(replies = normalizedReplies)
            val index = mainItemIndexByKey[mainKey]
            if (index == null) {
                detailItems.add(normalized)
                mainItemIndexByKey[mainKey] = detailItems.lastIndex
                changed++
            } else {
                val old = detailItems[index]
                val mergedReplies = mergeReplies(old.replies, normalized.replies)
                val mergedReplyCount = max(old.replyCount ?: 0, normalized.replyCount ?: 0)
                    .takeIf { it > 0 }
                if (mergedReplies.size != old.replies.size || mergedReplyCount != old.replyCount) {
                    detailItems[index] = old.copy(
                        replies = mergedReplies,
                        replyCount = mergedReplyCount
                    )
                    changed++
                }
            }
        }
        return changed
    }

    private fun dedupeReplies(replies: List<ReviewDetailItem>): List<ReviewDetailItem> {
        if (replies.isEmpty()) return replies
        val seen = HashSet<String>(replies.size)
        val result = ArrayList<ReviewDetailItem>(replies.size)
        replies.forEach { reply ->
            val key = buildDetailItemKey(reply, isReply = true)
            if (seen.add(key)) result.add(reply)
        }
        return result
    }

    private fun mergeReplies(
        oldReplies: List<ReviewDetailItem>,
        newReplies: List<ReviewDetailItem>
    ): List<ReviewDetailItem> {
        if (oldReplies.isEmpty()) return newReplies
        if (newReplies.isEmpty()) return oldReplies
        val result = ArrayList<ReviewDetailItem>(oldReplies.size + newReplies.size)
        result.addAll(oldReplies)
        val seen = HashSet<String>(oldReplies.size + newReplies.size)
        oldReplies.forEach { seen.add(buildDetailItemKey(it, isReply = true)) }
        newReplies.forEach { reply ->
            val key = buildDetailItemKey(reply, isReply = true)
            if (seen.add(key)) result.add(reply)
        }
        return result
    }

    private data class ReviewResult(
        val items: List<ReviewDetailItem>,
        val nextPageUrl: String?,
        val hasNextPageRule: Boolean
    )

    private data class ReviewDetailItem(
        val id: String?,
        val avatar: String?,
        val name: String?,
        val badges: List<String>,
        val content: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val replyCount: Int?,
        val replies: List<ReviewDetailItem>
    )

    private data class ReviewUiItem(
        val id: String?,
        val avatar: String?,
        val name: String?,
        val badges: List<String>,
        val content: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val isReply: Boolean,
        val itemType: Int = TYPE_NORMAL,
        val parentKey: String? = null,
        val moreCount: Int = 0
    )

    private fun flattenItems(items: List<ReviewDetailItem>): List<ReviewUiItem> {
        val list = ArrayList<ReviewUiItem>()
        items.forEach { item ->
            val parentKey = buildDetailItemKey(item, isReply = false)
            list.add(item.toUiItem(isReply = false, parentKey = parentKey))

            val loadedReplyCount = item.replies.size
            if (loadedReplyCount <= 0) return@forEach
            val moreCount = max(item.replyCount ?: 0, loadedReplyCount)

            if (expandedReplyParentKeys.contains(parentKey)) {
                item.replies.forEach { reply ->
                    list.add(reply.toUiItem(isReply = true, parentKey = parentKey))
                }
                return@forEach
            }

            list.add(
                ReviewUiItem(
                    id = null,
                    avatar = null,
                    name = null,
                    badges = emptyList(),
                    content = null,
                    imageUrl = null,
                    audioUrl = null,
                    time = null,
                    likeCount = null,
                    isReply = false,
                    itemType = TYPE_MORE,
                    parentKey = parentKey,
                    moreCount = moreCount
                )
            )
        }
        return list
    }

    private fun renderUiItems() {
        val uiItems = flattenItems(detailItems)
        adapter.setItems(uiItems, uiItemDiffCallback, skipDiff = true)
    }

    private fun ReviewDetailItem.toUiItem(isReply: Boolean, parentKey: String? = null) = ReviewUiItem(
        id = id,
        avatar = avatar,
        name = name,
        badges = badges,
        content = content,
        imageUrl = imageUrl,
        audioUrl = audioUrl,
        time = time,
        likeCount = likeCount,
        isReply = isReply,
        parentKey = parentKey
    )

    private companion object {
        const val TYPE_NORMAL = 0
        const val TYPE_MORE = 1
    }

    private data class ReviewParseResult(
        val items: List<ReviewDetailItem>,
        val nextPageUrl: String?
    )

    private data class ReviewContentProtocol(
        val text: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val replyCount: Int?
    )

    private fun parseReviewDetailList(
        body: String,
        rule: ReviewRule,
        nextPageRule: String?,
        baseUrl: String,
        source: io.legado.app.data.entities.BaseSource,
        book: io.legado.app.data.entities.Book,
        chapter: io.legado.app.data.entities.BookChapter,
        context: kotlin.coroutines.CoroutineContext,
        paraIndex: String,
        paraData: String,
        page: String
    ): ReviewParseResult {
        val listRule = rule.detailListRule?.trim().orEmpty()
        if (listRule.isEmpty()) return ReviewParseResult(emptyList(), null)
        val analyzeRule = AnalyzeRule(book, source)
            .setChapter(chapter)
            .setCoroutineContext(context)
            .setContent(body, baseUrl)
            .setLocal("paraIndex", paraIndex)
            .setLocal("paraData", paraData)
            .setLocal("page", page)
        val list = runCatching { analyzeRule.getElements(listRule) }.getOrElse {
            AppLog.put("段评详情列表规则执行出错\n${it.localizedMessage}", it)
            emptyList()
        }
        val nextPageUrl = if (!nextPageRule.isNullOrBlank()) {
            val raw = safeRuleString(analyzeRule, nextPageRule)?.trim().orEmpty()
            when {
                raw.isEmpty() -> null
                AnalyzeUrl.paramPattern.matcher(raw).find() -> raw
                else -> NetworkUtils.getAbsoluteURL(baseUrl, raw)
            }
        } else {
            null
        }
        if (list.isEmpty()) return ReviewParseResult(emptyList(), nextPageUrl)
        val items = list.mapNotNull {
            parseReviewDetailItem(
                analyzeRule = analyzeRule,
                item = it,
                rule = rule,
                baseUrl = baseUrl,
                isReply = false
            )
        }
        return ReviewParseResult(items, nextPageUrl)
    }

    private fun parseReviewDetailItem(
        analyzeRule: AnalyzeRule,
        item: Any,
        rule: ReviewRule,
        baseUrl: String,
        isReply: Boolean
    ): ReviewDetailItem? {
        analyzeRule.setContent(item, baseUrl)
        val avatarRule = if (isReply) rule.replyAvatarRule else rule.detailAvatarRule
        val nameRule = if (isReply) rule.replyNameRule else rule.detailNameRule
        val badgeRule = if (isReply) rule.replyBadgeRule else rule.detailBadgeRule
        val contentRule = if (isReply) rule.replyContentRule else rule.detailContentRule
        val idRule = if (isReply) rule.replyIdRule else rule.detailIdRule

        val avatar = safeRuleString(analyzeRule, avatarRule)
            ?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) }
        val name = safeRuleString(analyzeRule, nameRule)
        val rawContent = safeRuleString(analyzeRule, contentRule)
        val contentProtocol = parseReviewContentProtocol(rawContent, baseUrl)
        val content = if (contentProtocol != null) {
            contentProtocol.text ?: ""
        } else {
            rawContent
        }
        val imageUrl = contentProtocol?.imageUrl
        val audioUrl = contentProtocol?.audioUrl
        val time = contentProtocol?.time
        val id = safeRuleString(analyzeRule, idRule)
        val badges = safeRuleList(analyzeRule, badgeRule)
        val likeCount = if (isReply) null else contentProtocol?.likeCount
        val replyCount = if (isReply) null else contentProtocol?.replyCount

        val replies = if (!isReply && !rule.replyListRule.isNullOrBlank()) {
            val replyList = runCatching {
                analyzeRule.getElements(rule.replyListRule!!.trim())
            }.getOrElse { emptyList() }
            replyList.mapNotNull {
                parseReviewDetailItem(
                    analyzeRule = analyzeRule,
                    item = it,
                    rule = rule,
                    baseUrl = baseUrl,
                    isReply = true
                )
            }
        } else {
            emptyList()
        }

        if (name.isNullOrBlank() && content.isNullOrBlank() && imageUrl.isNullOrBlank() && audioUrl.isNullOrBlank()) {
            return null
        }
        return ReviewDetailItem(
            id = id,
            avatar = avatar,
            name = name,
            badges = badges,
            content = content,
            imageUrl = imageUrl,
            audioUrl = audioUrl,
            time = time,
            likeCount = likeCount,
            replyCount = replyCount,
            replies = replies
        )
    }

    private fun parseReviewContentProtocol(raw: String?, baseUrl: String): ReviewContentProtocol? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("{") || !text.endsWith("}")) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val t = obj.optString("text").trim().ifEmpty { null }
        val imgRaw = obj.optString("img").trim().ifEmpty { null }
        val audioRaw = obj.optString("audio").trim().ifEmpty { null }
        val timeRaw = obj.optString("time").trim().ifEmpty { null }
        val likeCountRaw = obj.opt("likeCount")
        val replyCountRaw = obj.opt("replyCount")
        val likeCount = parseProtocolInt(likeCountRaw)
        val replyCount = parseProtocolInt(replyCountRaw)
        if (t == null && imgRaw == null && audioRaw == null &&
            timeRaw == null && likeCount == null && replyCount == null
        ) return null
        return ReviewContentProtocol(
            text = t,
            imageUrl = imgRaw?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) },
            audioUrl = audioRaw?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) },
            time = timeRaw,
            likeCount = likeCount,
            replyCount = replyCount
        )
    }

    private fun parseProtocolInt(value: Any?): Int? {
        return when (value) {
            null, JSONObject.NULL -> null
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: value.trim().toDoubleOrNull()?.toInt()
            else -> value.toString().trim().toIntOrNull() ?: value.toString().trim().toDoubleOrNull()?.toInt()
        }
    }

    private fun safeRuleString(analyzeRule: AnalyzeRule, rule: String?): String? {
        val r = rule?.trim().orEmpty()
        if (r.isEmpty()) return null
        return runCatching { analyzeRule.getString(r) }
            .onFailure { AppLog.put("段评规则执行出错: $r\n${it.localizedMessage}", it) }
            .getOrDefault("")
            .takeIf { it.isNotBlank() }
    }

    private fun safeRuleList(analyzeRule: AnalyzeRule, rule: String?): List<String> {
        val r = rule?.trim().orEmpty()
        if (r.isEmpty()) return emptyList()
        val fromList = runCatching {
            analyzeRule.getStringList(r).orEmpty()
        }.onFailure {
            AppLog.put("段评规则执行出错: $r\n${it.localizedMessage}", it)
        }.getOrDefault(emptyList())
        if (fromList.isNotEmpty()) {
            return fromList.flatMap { splitBadgeValue(it) }.distinct()
        }
        val fromString = runCatching { analyzeRule.getString(r) }
            .onFailure { AppLog.put("段评规则执行出错: $r\n${it.localizedMessage}", it) }
            .getOrDefault("")
        return splitBadgeValue(fromString).distinct()
    }

    private fun splitBadgeValue(value: String?): List<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        if (raw.isDataUrl()) return listOf(raw)

        if (raw.startsWith("[") && raw.endsWith("]")) {
            val jsonArray = runCatching { JSONArray(raw) }.getOrNull()
            if (jsonArray != null) {
                val list = ArrayList<String>(jsonArray.length())
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optString(i).trim()
                    if (item.isNotEmpty()) list.add(item)
                }
                if (list.isNotEmpty()) return list
            }
        }

        val separator = when {
            raw.contains('\n') -> '\n'
            raw.contains('|') -> '|'
            raw.contains(',') && shouldSplitByComma(raw) -> ','
            else -> null
        }
        if (separator == null) return listOf(raw)

        return raw.split(separator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun shouldSplitByComma(raw: String): Boolean {
        if (raw.startsWith("data:")) return false
        if (!raw.contains("://")) return true
        return raw.contains(",http://") || raw.contains(",https://")
    }

    private inner class ReviewAdapter(context: Context) :
        RecyclerAdapter<ReviewUiItem, ItemReviewCommentBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemReviewCommentBinding {
            return ItemReviewCommentBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReviewCommentBinding,
            item: ReviewUiItem,
            payloads: MutableList<Any>
        ) {
            val tvContentLp = binding.tvContent.layoutParams as ViewGroup.MarginLayoutParams
            val basePadding = 8.dpToPx()
            val mainAvatarSize = 36.dpToPx()
            if (item.itemType == TYPE_MORE) {
                binding.root.updatePaddingRelative(
                    start = basePadding,
                    top = 2.dpToPx(),
                    end = basePadding,
                    bottom = 2.dpToPx()
                )
                binding.ivAvatar.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = mainAvatarSize
                    height = mainAvatarSize
                }
                binding.ivAvatar.visibility = View.INVISIBLE
                binding.llLikeArea.gone()
                binding.tvName.gone()
                binding.llBadges.gone()
                binding.tvTime.gone()
                binding.ivMedia.gone()
                binding.tvAudio.gone()
                binding.tvContent.visible()
                binding.tvContent.text = context.getString(R.string.review_more_replies, item.moreCount)
                binding.tvContent.textSize = 14f
                binding.tvContent.setTextColor(context.getCompatColor(R.color.accent))
                binding.tvContent.setPadding(0, 0, 0, 0)
                tvContentLp.topMargin = 0
                binding.tvContent.layoutParams = tvContentLp
                binding.llContentCard.background = null
                return
            }

            // 子评论缩进：使用主评论头像宽度作为占位，避免写死 magic number
            val replyIndent = mainAvatarSize
            val startPadding = if (item.isReply) basePadding + replyIndent else basePadding
            binding.root.updatePaddingRelative(
                start = startPadding,
                top = basePadding,
                end = basePadding,
                bottom = basePadding
            )

            binding.llContentCard.background = null

            val avatarSize = if (item.isReply) 28.dpToPx() else 36.dpToPx()
            binding.ivAvatar.updateLayoutParams<ViewGroup.LayoutParams> {
                width = avatarSize
                height = avatarSize
            }

            if (item.avatar.isNullOrBlank()) {
                binding.ivAvatar.gone()
            } else {
                binding.ivAvatar.visible()
                ImageLoader.load(context, item.avatar).circleCrop().into(binding.ivAvatar)
            }

            val primaryColor = context.getCompatColor(R.color.primaryText)
            val secondaryColor = context.getCompatColor(R.color.secondaryText)
            if (item.isReply) {
                binding.tvName.gone()
                binding.llBadges.gone()
                val name = item.name.orEmpty().trim()
                val content = item.content.orEmpty().trim()
                binding.tvContent.text = when {
                    name.isEmpty() -> content
                    content.isEmpty() -> name
                    else -> SpannableStringBuilder().apply {
                        append(name)
                        setSpan(
                            ForegroundColorSpan(secondaryColor),
                            0,
                            name.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        append("  ")
                        append(content)
                    }
                }
            } else {
                binding.tvName.text = item.name.orEmpty()
                binding.tvName.visibility = if (item.name.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.tvName.setTextColor(primaryColor)
                binding.llBadges.visibility = if (item.badges.isEmpty()) View.GONE else View.VISIBLE
                binding.tvContent.text = item.content.orEmpty()
            }
            val hasText = binding.tvContent.text?.isNotBlank() == true
            binding.tvContent.visibility = if (hasText) View.VISIBLE else View.GONE
            binding.tvContent.textSize = if (item.isReply) 14f else 16f
            binding.tvContent.setTextColor(primaryColor)
            binding.tvContent.setPadding(0, 0, 0, 0)
            tvContentLp.topMargin = if (item.isReply) 0 else 4.dpToPx()
            binding.tvContent.layoutParams = tvContentLp

            if (item.imageUrl.isNullOrBlank()) {
                binding.ivMedia.gone()
            } else {
                binding.ivMedia.visible()
                ImageLoader.load(context, item.imageUrl).into(binding.ivMedia)
            }

            if (item.audioUrl.isNullOrBlank()) {
                binding.tvAudio.gone()
            } else {
                binding.tvAudio.visible()
                binding.tvAudio.text = context.getString(R.string.review_play_audio)
                binding.tvAudio.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_play_24dp,
                    0,
                    0,
                    0
                )
            }

            binding.tvTime.text = item.time.orEmpty()
            binding.tvTime.visibility = if (item.time.isNullOrBlank()) View.GONE else View.VISIBLE
            if (!item.isReply) {
                binding.ivLike.visible()
                binding.llLikeArea.visible()
                val likeCount = item.likeCount
                if (likeCount != null && likeCount > 0) {
                    binding.tvLikeCount.text = likeCount.toString()
                    binding.tvLikeCount.visible()
                } else {
                    binding.tvLikeCount.gone()
                }
            } else {
                binding.ivLike.gone()
                binding.tvLikeCount.gone()
                binding.llLikeArea.gone()
            }

            if (!item.isReply) {
                bindBadges(binding.llBadges, item.badges)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReviewCommentBinding) {
            binding.root.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                if (item.itemType == TYPE_MORE) {
                    val parentKey = item.parentKey ?: return@setOnClickListener
                    val canExpand = detailItems.any {
                        buildDetailItemKey(it, isReply = false) == parentKey && it.replies.isNotEmpty()
                    }
                    if (canExpand && expandedReplyParentKeys.add(parentKey)) {
                        renderUiItems()
                    }
                }
            }
            binding.ivMedia.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                val imageUrl = item.imageUrl ?: return@setOnClickListener
                PhotoDialog(imageUrl).show(this@ReviewDetailDialog.childFragmentManager, "reviewPhoto")
            }
            binding.tvAudio.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                val audioUrl = item.audioUrl ?: return@setOnClickListener
                context.openUrl(audioUrl)
            }
        }

        private fun bindBadges(container: FlexboxLayout, badges: List<String>) {
            container.removeAllViews()
            if (badges.isEmpty()) {
                container.gone()
                return
            }
            container.visible()
            badges.forEach { badge ->
                if (badge.isAbsUrl() || badge.isDataUrl()) {
                    val iv = ImageView(context).apply {
                        layoutParams = FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            16.dpToPx()
                        ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    ImageLoader.load(context, badge).into(iv)
                    container.addView(iv)
                } else {
                    val tv = TextView(context).apply {
                        layoutParams = FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                        setPadding(6.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
                        setTextColor(context.getCompatColor(R.color.secondaryText))
                        textSize = 11f
                        text = badge
                        setBackgroundResource(R.drawable.bg_review_badge_chip)
                    }
                    container.addView(tv)
                }
            }
        }
    }
}
