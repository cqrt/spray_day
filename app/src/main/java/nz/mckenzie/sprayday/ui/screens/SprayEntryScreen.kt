package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.viewmodel.SprayEntryViewModel

/**
 * The pre-spray form: which products, and how much of each.
 *
 * Amounts arrive pre-filled from the track's last spray, because a set track is
 * sprayed the same way three times a year and retyping the mix is where mistakes
 * creep in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayEntryScreen(
    viewModel: SprayEntryViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val track by viewModel.track.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val waterLitres by viewModel.waterLitres.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val remember by viewModel.rememberDefaults.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var addingProduct by remember { mutableStateOf(false) }
    var newProductName by remember { mutableStateOf("") }
    var managingProducts by remember { mutableStateOf(false) }
    var renamingProduct by remember { mutableStateOf<ProductEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    val allProducts by viewModel.allProducts.collectAsStateWithLifecycle()

    LaunchedEffect(saved) {
        if (saved) {
            // Consume before navigating, so re-entering the form for this track
            // does not immediately bounce straight back out.
            viewModel.consumeSaveResult()
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spray ${track?.name ?: "track"}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Products and amounts", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "What is going out on this pass. Amounts are pre-filled from the " +
                    "last time this track was sprayed.",
                style = MaterialTheme.typography.bodySmall
            )

            rows.forEach { row ->
                OutlinedTextField(
                    value = row.quantityText,
                    onValueChange = { viewModel.updateQuantity(row.productId, it) },
                    label = { Text(row.name) },
                    suffix = { Text("mL") },
                    supportingText = row.rateText?.let { rate -> { Text(rate) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (rows.isEmpty()) {
                Text(
                    text = "No products yet. Add the first one below.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedButton(onClick = { addingProduct = true }) { Text("Add product") }
            OutlinedButton(onClick = { managingProducts = true }) { Text("Manage products") }

            OutlinedTextField(
                value = waterLitres,
                onValueChange = viewModel::setWaterLitres,
                label = { Text("Water used (L)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = notes,
                onValueChange = viewModel::setNotes,
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = remember, onCheckedChange = viewModel::setRememberDefaults)
                Text(
                    text = "Remember these amounts for this track",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            message?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text("Save spray")
            }
        }
    }

    if (addingProduct) {
        AlertDialog(
            onDismissRequest = { addingProduct = false },
            title = { Text("Add a product") },
            text = {
                OutlinedTextField(
                    value = newProductName,
                    onValueChange = { newProductName = it },
                    label = { Text("Product name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    addingProduct = false
                    viewModel.addProduct(newProductName)
                    newProductName = ""
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { addingProduct = false }) { Text("Cancel") }
            }
        )
    }

    if (managingProducts) {
        AlertDialog(
            onDismissRequest = { managingProducts = false },
            title = { Text("Products") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Archiving keeps a product out of the entry form. Sprays that " +
                            "already used it keep their history.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (allProducts.isEmpty()) {
                        Text("No products yet.", style = MaterialTheme.typography.bodySmall)
                    }
                    allProducts.forEach { product ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (product.archived) "${product.name} (archived)" else product.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = {
                                renamingProduct = product
                                renameText = product.name
                            }) { Text("Rename") }
                            TextButton(
                                onClick = { viewModel.setProductArchived(product.id, !product.archived) }
                            ) { Text(if (product.archived) "Restore" else "Archive") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { managingProducts = false }) { Text("Done") }
            }
        )
    }

    renamingProduct?.let { product ->
        AlertDialog(
            onDismissRequest = { renamingProduct = null },
            title = { Text("Rename product") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Product name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameProduct(product.id, renameText)
                    renamingProduct = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renamingProduct = null }) { Text("Cancel") }
            }
        )
    }
}
