package app.receiptscanner.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.receiptscanner.BuildConfig
import app.receiptscanner.data.AppSettings
import app.receiptscanner.data.QueuedReceipt
import app.receiptscanner.data.ReceiptQueue
import app.receiptscanner.data.SettingsRepo
import app.receiptscanner.data.UploadResult
import app.receiptscanner.data.Uploader
import app.receiptscanner.ocr.Ocr
import app.receiptscanner.parse.ClaudeParser
import app.receiptscanner.parse.Item
import app.receiptscanner.parse.Receipt
import app.receiptscanner.parse.ReceiptJson
import app.receiptscanner.parse.RegexParser
import app.receiptscanner.parse.RowBuilder
import app.receiptscanner.parse.round2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class Screen { CAMERA, PROCESSING, REVIEW, SETTINGS }

/** Editable item row; fields are strings so the user can type freely. */
class ItemDraft(name: String, qty: String, unit: String, total: String) {
    var name by mutableStateOf(name)
    var qty by mutableStateOf(qty)
    var unit by mutableStateOf(unit)
    var total by mutableStateOf(total)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepo(app)
    private val queue = ReceiptQueue(app)
    private val photosDir = File(app.filesDir, "photos").apply { mkdirs() }

    var screen by mutableStateOf(Screen.CAMERA)
    var progress by mutableStateOf("")
    var toast by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var pendingCount by mutableStateOf(queue.count())

    // Review draft
    var hasDraft by mutableStateOf(false)
    var store by mutableStateOf("")
    var date by mutableStateOf("")
    var time by mutableStateOf("")
    var total by mutableStateOf("")
    var paymentTotal by mutableStateOf("")
    val items = mutableStateListOf<ItemDraft>()
    var parsedBy by mutableStateOf("")
    var ocrText by mutableStateOf("")
    private var photoFile: File? = null
    private var photoTimestamp: String = ""

    init {
        if (queue.count() > 0) Uploader.scheduleRetry(app)
    }

    fun newPhotoFile(): File = File(photosDir, "IMG_${System.currentTimeMillis()}.jpg")

    fun onCaptured(file: File) = process(file, nowIso())

    fun onPicked(uri: Uri) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val file = newPhotoFile()
        val ts = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            exifTimestamp(file) ?: nowIso()
        }
        process(file, ts)
    }

    private fun process(file: File, ts: String) {
        photoFile = file
        photoTimestamp = ts
        screen = Screen.PROCESSING
        viewModelScope.launch {
            try {
                progress = "Reading text…"
                val text = Ocr.read(getApplication(), Uri.fromFile(file))
                ocrText = text
                if (text.isBlank()) {
                    loadDraft(Receipt(), "No text detected")
                    return@launch
                }
                progress = "Understanding receipt…"
                val (receipt, by) = withContext(Dispatchers.IO) { parse(text) }
                loadDraft(receipt, by)
            } catch (e: Exception) {
                loadDraft(Receipt(), "Error: ${e.message}")
            }
        }
    }

    private fun parse(text: String): Pair<Receipt, String> {
        val key = BuildConfig.ANTHROPIC_API_KEY
        var note = "offline parser (no API key)"
        if (key.isNotBlank()) {
            try {
                return ClaudeParser(key, BuildConfig.CLAUDE_MODEL).parse(text) to "Claude"
            } catch (e: Exception) {
                note = "offline parser (Claude failed: ${e.javaClass.simpleName})"
            }
        }
        val r = RegexParser.parse(text)
        return (if (r.items.isEmpty()) r else ReceiptJson.normalize(r)) to note
    }

    private fun loadDraft(r: Receipt, by: String) {
        store = r.store.orEmpty()
        date = r.date.orEmpty()
        time = r.time.orEmpty()
        total = r.total?.let(::fmt).orEmpty()
        paymentTotal = r.paymentTotal?.let(::fmt).orEmpty()
        items.clear()
        r.items.forEach { items += ItemDraft(it.name, fmtQty(it.qty), it.unitPrice?.let(::fmt).orEmpty(), fmt(it.lineTotal)) }
        parsedBy = by
        hasDraft = true
        screen = Screen.REVIEW
    }

    fun addItem() { items += ItemDraft("", "1", "", "") }

    val itemsSum: Double get() = round2(items.sumOf { num(it.total) ?: 0.0 })
    val totalValue: Double? get() = num(total)
    val mismatch: Boolean get() = totalValue?.let { kotlin.math.abs(it - itemsSum) >= 0.005 } ?: true

    fun retake() {
        photoFile?.delete()
        clearDraft()
    }

    private fun clearDraft() {
        hasDraft = false
        items.clear()
        photoFile = null
        screen = Screen.CAMERA
    }

    fun save() = viewModelScope.launch {
        val settings = settingsRepo.get()
        if (!settings.isComplete) {
            toast = "Set the script URL, secret and who you are, then Save again"
            screen = Screen.SETTINGS
            return@launch
        }
        val receipt = Receipt(
            store = store.trim().ifEmpty { null },
            date = ReceiptJson.normalizeDate(date),
            time = ReceiptJson.normalizeTime(time),
            items = items.filter { it.name.isNotBlank() && num(it.total) != null }.map {
                Item(it.name.trim(), num(it.qty) ?: 1.0, num(it.unit), num(it.total)!!)
            },
            total = num(total),
            paymentTotal = num(paymentTotal),
        )
        if (receipt.items.isEmpty()) { toast = "No items to save"; return@launch }
        if (date.isNotBlank() && receipt.date == null) { toast = "Invalid date (use yyyy-mm-dd)"; return@launch }

        val q = QueuedReceipt(
            receiptId = RowBuilder.receiptId(receipt, photoTimestamp),
            receipt = receipt,
            photoTimestamp = photoTimestamp,
            addedBy = settings.addedBy,
            photoPath = photoFile?.absolutePath,
        )
        saving = true
        withContext(Dispatchers.IO) { queue.add(q) } // durable before any network
        val result = Uploader.upload(q, settings)
        saving = false
        when (result) {
            is UploadResult.Ok -> {
                queue.remove(q.receiptId)
                toast = if (result.duplicate) "Already in the sheet (duplicate)" else "Saved to sheet ✓"
            }
            is UploadResult.Retry -> {
                Uploader.scheduleRetry(getApplication())
                toast = "Offline: queued, will upload later"
            }
            is UploadResult.Failed -> {
                Uploader.scheduleRetry(getApplication())
                toast = "Queued. Sheet error: ${result.reason}"
            }
        }
        pendingCount = queue.count()
        clearDraft()
    }

    // Settings
    suspend fun loadSettings(): AppSettings = settingsRepo.get()

    fun saveSettings(s: AppSettings) = viewModelScope.launch {
        settingsRepo.save(s)
        toast = "Settings saved"
        closeSettings()
    }

    fun openSettings() { pendingCount = queue.count(); screen = Screen.SETTINGS }
    fun closeSettings() { screen = if (hasDraft) Screen.REVIEW else Screen.CAMERA }

    fun retryNow() = viewModelScope.launch {
        val left = Uploader.drain(getApplication())
        pendingCount = queue.count()
        if (left) Uploader.scheduleRetry(getApplication())
        toast = if (pendingCount == 0) "Queue empty ✓" else "$pendingCount still queued"
    }

    private fun nowIso(): String =
        ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun exifTimestamp(file: File): String? = runCatching {
        val exif = ExifInterface(file)
        val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        val ldt = LocalDateTime.parse(dt, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        ldt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }.getOrNull()

    companion object {
        fun num(s: String): Double? = s.trim().replace("€", "").replace(" ", "").replace(',', '.').toDoubleOrNull()
        fun fmt(v: Double): String = "%.2f".format(java.util.Locale.ROOT, v).replace('.', ',')
        fun fmtQty(v: Double): String =
            if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')
    }
}
