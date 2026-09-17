package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.ui.BlockEditFields
import nz.mckenzie.sprayday.ui.BlockEdits
import nz.mckenzie.sprayday.viewmodel.BlockEditViewModel

/**
 * One block: its name, its notes, and the two things that can be done to it.
 *
 * A screen rather than a dialog, for the reason the asset form is one: the notes are a field
 * people write in, and a dialog with a keyboard up has nowhere to put them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditScreen(viewModel: BlockEditViewModel, onDone: () -> Unit) {
    val block by viewModel.block.collectAsStateWithLifecycle()
    val assetCount by viewModel.assetCount.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()

    // Saved or deleted: the work is finished, so leave rather than sit on a form whose subject
    // has gone. Nothing else navigates from here, which is what keeps a refusal on screen.
    LaunchedEffect(done) { if (done) onDone() }

    // Nothing until the block is here: a form that opened with a blank name would save that
    // name over a real one.
    val current = block ?: return
    var name by remember { mutableStateOf(current.name) }
    var notes by remember { mutableStateOf(current.notes.orEmpty()) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.name) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        AppIcon(IconGlyph.BACK, contentDescription = "Back")
                    }
                }
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; viewModel.clearProblem() },
                label = { Text("Name") },
                supportingText = { Text("Assets are gathered by this name, so two blocks cannot share one") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                supportingText = { Text("What this block is for, if it needs saying") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (assetCount == 1) "1 asset in this block" else "$assetCount assets in this block",
                style = MaterialTheme.typography.bodyMedium
            )
            problem?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = { viewModel.save(BlockEditFields(name = name, notes = notes)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            DestructiveOutlinedButton(
                text = "Delete this block",
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this block?") },
            text = { Text(BlockEdits.deleteMessage(current.name, assetCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.delete()
                    },
                    colors = destructiveTextButtonColors()
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            }
        )
    }
}
