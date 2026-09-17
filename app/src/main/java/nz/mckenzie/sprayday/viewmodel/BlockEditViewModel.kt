package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.GroupEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.ui.BlockEditFields
import nz.mckenzie.sprayday.ui.BlockEditResult
import nz.mckenzie.sprayday.ui.BlockEdits

/**
 * One block: its name, its notes, and the two things that can be done to it.
 *
 * The checks are [BlockEdits]'s, which is pure and tested, and they run here rather than in the
 * screen because a rename needs the block's own name and the names of every other block to
 * answer at all - and this is where those two are already in hand.
 *
 * Both actions end in [done] rather than a callback: the screen goes back when the write has
 * actually happened, so a failure stays on the form with the reason under it instead of
 * looking like a success and losing the typing.
 */
class BlockEditViewModel(
    private val blockId: Long,
    private val assetRepository: AssetRepository
) : ViewModel() {

    val block: StateFlow<GroupEntity?> = assetRepository.observeGroup(blockId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Every block name, so a rename can be answered with a sentence rather than a unique index. */
    val blockNames: StateFlow<List<String>> = assetRepository.observeBlockNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * How many assets are in this block, for the sentence the delete confirmation says.
     *
     * Counted by name, because a name is how an asset knows its block - and the count is worth
     * having in front of the operator at the moment they are deciding whether to delete the
     * thing those assets are filed under.
     */
    val assetCount: StateFlow<Int> = combine(
        assetRepository.observeGroup(blockId),
        assetRepository.observeAssetsWithDue()
    ) { group, assets ->
        val name = group?.name ?: return@combine 0
        assets.count { it.groupName?.equals(name, ignoreCase = true) == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    /** Clears the last refusal, so a message does not sit under a field being retyped. */
    fun clearProblem() {
        _problem.value = null
    }

    private val _done = MutableStateFlow(false)

    /** True once the block has been saved or deleted, whether or not it still exists. */
    val done: StateFlow<Boolean> = _done

    fun save(fields: BlockEditFields) {
        val current = block.value ?: return
        when (val result = BlockEdits.apply(current.name, fields, blockNames.value)) {
            is BlockEditResult.Invalid -> _problem.value = result.message

            is BlockEditResult.Ok -> viewModelScope.launch {
                runCatching { assetRepository.saveBlockEdits(blockId, result.name, result.notes) }
                    .onSuccess { _done.value = true }
                    .onFailure { _problem.value = it.message ?: "Could not save the block" }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { assetRepository.deleteBlock(blockId) }
                .onSuccess { _done.value = true }
                .onFailure { _problem.value = it.message ?: "Could not delete the block" }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context, blockId: Long): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    BlockEditViewModel(
                        blockId = blockId,
                        assetRepository = AssetRepository(SprayDayDatabase.get(appContext))
                    )
                }
            }
        }
    }
}
