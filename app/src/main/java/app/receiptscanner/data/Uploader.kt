package app.receiptscanner.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.receiptscanner.parse.ReceiptJson
import app.receiptscanner.parse.RowBuilder
import app.receiptscanner.parse.SheetRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface UploadResult {
    data class Ok(val duplicate: Boolean) : UploadResult
    /** Network trouble — retry later. */
    data class Retry(val reason: String) : UploadResult
    /** Script answered with an error (bad secret, missing column...) — needs a settings fix. */
    data class Failed(val reason: String) : UploadResult
}

object Uploader {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build() // follows the Apps Script 302 redirect to fetch the response

    suspend fun upload(q: QueuedReceipt, settings: AppSettings): UploadResult = withContext(Dispatchers.IO) {
        if (!settings.isComplete) return@withContext UploadResult.Failed("Settings incomplete")
        val rows = RowBuilder.rows(q.receipt, q.photoTimestamp, q.addedBy)
        val body = buildJsonObject {
            put("token", settings.secret)
            put("receipt_id", q.receiptId)
            put("rows", ReceiptJson.json.encodeToJsonElement(ListSerializer(SheetRow.serializer()), rows))
        }.toString()
        val req = Request.Builder()
            .url(settings.scriptUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext UploadResult.Retry("HTTP ${resp.code}")
                val obj = runCatching { ReceiptJson.json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return@withContext UploadResult.Failed("Unexpected response (is the URL the /exec web-app URL?)")
                val ok = (obj["ok"] as? JsonPrimitive)?.boolean == true
                if (ok) UploadResult.Ok(duplicate = (obj["duplicate"] as? JsonPrimitive)?.boolean == true)
                else UploadResult.Failed((obj["error"] as? JsonPrimitive)?.content ?: "unknown error")
            }
        } catch (e: IOException) {
            UploadResult.Retry(e.message ?: "network error")
        } catch (e: IllegalArgumentException) {
            UploadResult.Failed("Invalid script URL")
        }
    }

    /** Uploads every queued receipt; removes the confirmed ones. Returns true if anything is left to retry. */
    suspend fun drain(context: Context): Boolean {
        val queue = ReceiptQueue(context)
        val settings = SettingsRepo(context).get()
        var needsRetry = false
        for (q in queue.all()) {
            when (upload(q, settings)) {
                is UploadResult.Ok -> queue.remove(q.receiptId)
                is UploadResult.Retry, is UploadResult.Failed -> needsRetry = true
            }
        }
        return needsRetry
    }

    fun scheduleRetry(context: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (Uploader.drain(applicationContext)) Result.retry() else Result.success()
}
