package com.shiyi.app.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyi.app.AppContainer
import com.shiyi.app.data.FoodItemEntity
import com.shiyi.app.data.LinkDraft
import com.shiyi.app.data.RecordEntity
import com.shiyi.app.data.RecordImageEntity
import com.shiyi.app.data.Repository
import com.shiyi.app.domain.ItemStatus
import com.shiyi.app.domain.Money
import com.shiyi.app.domain.Platform
import com.shiyi.app.domain.ReasonTag
import com.shiyi.app.domain.RepurchaseIntent
import com.shiyi.app.domain.Scenario
import com.shiyi.app.ocr.ParsedOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RecordEditViewModel(
    private val c: AppContainer,
    private val recordId: String?,
    initialScenario: String?,
    private val fromImport: Boolean,
) : ViewModel() {
    private val repo = c.repo
    val isNew = recordId == null
    private val id = recordId ?: UUID.randomUUID().toString()

    var loaded by mutableStateOf(isNew)
        private set

    var scenario by mutableStateOf(Scenario.of(initialScenario) ?: Scenario.DELIVERY)
    var platform by mutableStateOf(Platform.defaultFor(scenario))
    private var platformTouched = false
    var shop by mutableStateOf("")
    var title by mutableStateOf("")
    var paidText by mutableStateOf("")
    private var orderTimeState by mutableStateOf(System.currentTimeMillis())
    var orderTime: Long
        get() = orderTimeState
        set(v) { orderTimeState = v; timeGuessed = false }
    var consumeTime by mutableStateOf<Long?>(null)
    var rating by mutableStateOf<Int?>(null)
    val reasons = mutableStateListOf<ReasonTag>()
    var note by mutableStateOf("")
    var intent by mutableStateOf<RepurchaseIntent?>(null)
    var keywords by mutableStateOf("")
    val links = mutableStateListOf<LinkDraft>()

    val existingImages = mutableStateListOf<RecordImageEntity>()
    private val removedImageIds = mutableListOf<String>()
    val stagedImages = mutableStateListOf<File>()
    var keepImages by mutableStateOf(false)

    private var orderNoHash: String? = null
    private var createdAt: Long = System.currentTimeMillis()
    private var originalRating: Int? = null

    /** 识别结果（候选项展示用） */
    var parsed by mutableStateOf<ParsedOrder?>(null)
        private set
    var sharedText by mutableStateOf<String?>(null)
        private set
    var importInfo by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
        private set
    var duplicate by mutableStateOf<RecordEntity?>(null)
    var savedId by mutableStateOf<String?>(null)
        private set
    private var saved = false

    /** 同店同标题的历史消费（输入店名和标题时即时提示，不弹窗） */
    val sameDish = snapshotFlow { shop.trim() to title.trim() }.debounce(300).distinctUntilChanged()
        .flatMapLatest { (sh, t) -> repo.sameDish(sh, t, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 识别结果里没有时间，正在用“今天”代替 */
    var timeGuessed by mutableStateOf(false)
        private set

    val shopHistory = snapshotFlow { shop.trim() }.debounce(300).distinctUntilChanged()
        .flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else repo.sameShop(it, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val shopSuggestions = snapshotFlow { shop }.debounce(250).distinctUntilChanged()
        .map { s -> runCatching { repo.shopSuggestions(s) }.getOrDefault(emptyList()).filter { it != s.trim() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val settings = c.settings.settings.first()
            keepImages = settings.keepImagesByDefault
            try {
                if (recordId != null) loadExisting(recordId)
                if (fromImport) applyPrefill(settings.storeOrderFingerprint)
            } catch (t: Throwable) {
                com.shiyi.app.diagnostics.CrashLog.append(c.appContext, "【载入编辑页失败】" + t.stackTraceToString().take(3000))
                error = "部分内容载入失败，请手动填写"
                loaded = true
            }
        }
    }

    private suspend fun loadExisting(rid: String) {
        val r = repo.getRecord(rid) ?: run { error = "记录不存在"; loaded = true; return }
        scenario = Scenario.of(r.scenario) ?: Scenario.DELIVERY
        platform = Platform.of(r.platform) ?: Platform.OTHER
        platformTouched = true
        shop = r.shopName
        title = r.title
        paidText = Money.editable(r.paidCents)
        orderTime = r.orderTime
        consumeTime = r.consumeTime
        rating = r.rating
        originalRating = r.rating
        reasons.clear(); reasons.addAll(ReasonTag.parse(r.reasonTags))
        note = r.note
        intent = RepurchaseIntent.of(r.repurchaseIntent)
        keywords = r.keywords
        orderNoHash = r.orderNoHash
        createdAt = r.createdAt
        links.clear()
        links.addAll(repo.linksForRecordOnce(rid).map {
            LinkDraft(it.item.id, it.item.name, it.item.shopName, it.item.spec, it.link.isSole, it.link.itemPriceCents, ItemStatus.of(it.item.status))
        })
        existingImages.clear(); existingImages.addAll(repo.imagesForRecordOnce(rid))
        loaded = true
    }

    private fun applyPrefill(storeFingerprint: Boolean) {
        val p = c.prefill.value ?: return
        c.prefill.value = null
        val o = p.parsed
        parsed = o
        sharedText = p.sharedText
        stagedImages.clear(); stagedImages.addAll(p.images)
        o.scenario?.let { scenario = it }
        platform = o.platform ?: Platform.defaultFor(scenario)
        platformTouched = o.platform != null
        o.shopName?.let { shop = it }
        o.title?.let { title = it }
        o.bestAmountCents?.let { paidText = Money.editable(it) }
        o.orderTime?.let { orderTime = it }
        timeGuessed = o.orderTime == null
        if (scenario == Scenario.GROUP_BUY) consumeTime = o.consumeTime
        keywords = o.keywords
        orderNoHash = if (storeFingerprint) o.orderNo?.let(Repository::orderNoFingerprint) else null
        importInfo = listOfNotNull(
            when {
                p.imageCount == 0 && p.sharedText != null ->
                    "已收到分享的文字/链接。平台链接通常无法读取订单详情；建议截图订单页后再导入以自动识别。"
                p.ocrFailed -> "截图识别失败，请手动填写。"
                else -> "已识别 ${p.imageCount} 张截图。识别不确定的字段已留空，请核对后保存。"
            },
            p.note,
        ).joinToString("\n")
        if (p.sharedText != null && note.isBlank()) note = p.sharedText.take(500)
        loaded = true
    }

    fun changeScenario(s: Scenario) {
        scenario = s
        if (!platformTouched || (s == Scenario.DINE_IN) || platform == Platform.OFFLINE) platform = Platform.defaultFor(s)
        if (s != Scenario.GROUP_BUY) consumeTime = null
        if (s != Scenario.DELIVERY) reasons.removeAll { it.delivery }
    }

    fun changePlatform(p: Platform) { platform = p; platformTouched = true }

    fun toggleReason(t: ReasonTag) { if (t in reasons) reasons.remove(t) else reasons.add(t) }

    fun removeExistingImage(img: RecordImageEntity) { existingImages.remove(img); removedImageIds += img.id }

    fun removeStaged(f: File) { stagedImages.remove(f); c.staging.discard(listOf(f)) }

    fun save(ignoreDuplicate: Boolean = false) {
        if (saving) return
        val shopName = shop.trim()
        val cents = Money.parseYuan(paidText)
        error = when {
            shopName.isEmpty() -> "请填写店铺名称"
            cents == null -> "请填写本人实付金额（最多两位小数）"
            else -> null
        }
        if (error != null) return
        val record = RecordEntity(
            id = id,
            shopName = shopName,
            title = title.trim(),
            scenario = scenario.name,
            platform = platform.name,
            paidCents = cents!!,
            orderTime = orderTime,
            consumeTime = if (scenario == Scenario.GROUP_BUY) consumeTime else null,
            rating = rating,
            reasonTags = ReasonTag.encode(reasons),
            note = note.trim(),
            repurchaseIntent = intent?.name,
            keywords = keywords.trim(),
            orderNoHash = orderNoHash,
            createdAt = createdAt,
            updatedAt = System.currentTimeMillis(),
        )
        saving = true
        viewModelScope.launch {
            try {
                if (isNew && !ignoreDuplicate) {
                    repo.findDuplicate(record)?.let { duplicate = it; return@launch }
                }
                val finalLinks = links.map { l -> if (l.isSole) l.copy(itemPriceCents = cents) else l }
                repo.saveRecord(record, finalLinks, if (keepImages) stagedImages.toList() else emptyList(), removedImageIds.toList())
                saved = true
                c.staging.discard(stagedImages.toList())
                c.pendingImport.value = null
                savedId = id
            } catch (t: Throwable) {
                error = "保存失败：" + (t.message ?: t.javaClass.simpleName)
            } finally {
                saving = false
            }
        }
    }

    /** 放弃编辑时清理未保存的临时图片 */
    override fun onCleared() {
        if (!saved) c.staging.discard(stagedImages.toList())
        if (fromImport) c.pendingImport.value = null
        super.onCleared()
    }
}
