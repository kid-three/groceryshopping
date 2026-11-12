package app.receiptscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReviewScreen(vm: MainViewModel) {
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    var showOcr by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("Review receipt", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                Text("Parsed by: ${vm.parsedBy}", style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(vm.store, { vm.store = it }, label = { Text("Store") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(vm.date, { vm.date = it }, label = { Text("Date yyyy-mm-dd") }, singleLine = true, modifier = Modifier.weight(1.4f))
                    OutlinedTextField(vm.time, { vm.time = it }, label = { Text("Time") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(vm.total, { vm.total = it }, label = { Text("TOTAL €") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
                    OutlinedTextField(vm.paymentTotal, { vm.paymentTotal = it }, label = { Text("Paid €") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
                }
            }
            item {
                val sum = MainViewModel.fmt(vm.itemsSum)
                if (vm.mismatch) {
                    val diff = vm.totalValue?.let { MainViewModel.fmt(it - vm.itemsSum) } ?: "?"
                    Text(
                        "⚠ Items add up to $sum € but TOTAL is ${vm.total.ifBlank { "?" }} € (difference $diff €). Check the lines before saving.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
                    )
                } else {
                    Text("✓ Items add up to $sum €, matching TOTAL", color = MaterialTheme.colorScheme.primary)
                }
            }
            itemsIndexed(vm.items) { index, it ->
                Column {
                    Row {
                        OutlinedTextField(it.name, { v -> it.name = v }, label = { Text("Item ${index + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.items.removeAt(index) }) { Icon(Icons.Default.Delete, "Delete item") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(it.qty, { v -> it.qty = v }, label = { Text("Qty/kg") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
                        OutlinedTextField(it.unit, { v -> it.unit = v }, label = { Text("Unit €") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
                        OutlinedTextField(it.total, { v -> it.total = v }, label = { Text("Line €") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
            item {
                Row {
                    TextButton(onClick = { vm.addItem() }) { Text("+ Add item") }
                    TextButton(onClick = { showOcr = !showOcr }) { Text(if (showOcr) "Hide OCR text" else "Show OCR text") }
                }
                if (showOcr) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(vm.ocrText)) }) { Text("Copy OCR text") }
                    Text(vm.ocrText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.retake() }, enabled = !vm.saving, modifier = Modifier.weight(1f)) { Text("Retake") }
            Button(onClick = { vm.save() }, enabled = !vm.saving, modifier = Modifier.weight(1f)) {
                Text(if (vm.saving) "Saving…" else if (vm.mismatch) "Save anyway" else "Save")
            }
        }
    }
}
