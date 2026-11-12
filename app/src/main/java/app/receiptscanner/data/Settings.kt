package app.receiptscanner.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.receiptscanner.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(val scriptUrl: String, val secret: String, val addedBy: String) {
    val isComplete get() = scriptUrl.isNotBlank() && secret.isNotBlank() && addedBy.isNotBlank()
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepo(private val context: Context) {
    private val kUrl = stringPreferencesKey("script_url")
    private val kSecret = stringPreferencesKey("secret")
    private val kAddedBy = stringPreferencesKey("added_by")

    val flow: Flow<AppSettings> = context.dataStore.data.map {
        AppSettings(
            scriptUrl = it[kUrl] ?: BuildConfig.DEFAULT_SCRIPT_URL,
            secret = it[kSecret] ?: BuildConfig.DEFAULT_SECRET,
            addedBy = it[kAddedBy] ?: "",
        )
    }

    suspend fun get(): AppSettings = flow.first()

    suspend fun save(s: AppSettings) {
        context.dataStore.edit {
            it[kUrl] = s.scriptUrl.trim()
            it[kSecret] = s.secret.trim()
            it[kAddedBy] = s.addedBy
        }
    }

    companion object {
        val PEOPLE = listOf("Kinich", "Alba")
    }
}
