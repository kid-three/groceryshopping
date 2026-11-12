package app.receiptscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.receiptscanner.BuildConfig
import app.receiptscanner.data.AppSettings
import app.receiptscanner.data.SettingsRepo

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var url by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var who by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val s = vm.loadSettings()
        url = s.scriptUrl; secret = s.secret; who = s.addedBy
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text("Who am I?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsRepo.PEOPLE.forEach { name ->
                FilterChip(selected = who == name, onClick = { who = name }, label = { Text(name) })
            }
        }
        OutlinedTextField(url, { url = it }, label = { Text("Apps Script web app URL (/exec)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            secret, { secret = it }, label = { Text("Shared secret") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Claude parsing: " + if (BuildConfig.ANTHROPIC_API_KEY.isNotBlank()) "on (${BuildConfig.CLAUDE_MODEL})" else "off — offline parser only",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Waiting to upload: ${vm.pendingCount}")
        OutlinedButton(onClick = { vm.retryNow() }, enabled = vm.pendingCount > 0) { Text("Retry uploads now") }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.closeSettings() }) { Text("Back") }
            Button(onClick = { vm.saveSettings(AppSettings(url, secret, who)) }) { Text("Save") }
        }
    }
}
