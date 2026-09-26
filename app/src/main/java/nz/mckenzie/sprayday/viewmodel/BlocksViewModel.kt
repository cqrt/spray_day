package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.asset.AssetGrouping
import nz.mckenzie.sprayday.domain.asset.GroupTotals
import nz.mckenzie.sprayday.domain.asset.GroupableAsset

/** One block on the screen that keeps them tidy: its name, its notes, and what it holds. */
data class BlockRow(
    val id: Long,
    val name: String,
    val notes: String?,
    val totals: GroupTotals
) {
    /**
     * A block with nothing in it is a leftover, not a mistake: they are made by naming one on
     * an asset and taken apart by taking those names away again, so the screen says so plainly
     * and offers the way out.
     */
    val isEmpty: Boolean get() = totals.assetCount == 0
}

/**
 * Every block, with what each of them comes to.
 *
 * The list comes from the blocks themselves rather than from the assets in them, which is the
 * one place this differs from the asset list: there, a block exists because assets point at
 * it; here, a block that nobody is in any more still has to be visible, because renaming or
 * deleting it is the whole reason the screen exists.
 *
 * The arithmetic is [AssetGrouping]'s, the same one the collapsed tile in the asset list uses,
 * so a block cannot come to two different sums on two screens.
 */
class BlocksViewModel(private val assetRepository: AssetRepository) : ViewModel() {

    val blocks: StateFlow<List<BlockRow>> = combine(
        assetRepository.observeGroups(),
        assetRepository.observeAssetsWithDue()
    ) { groups, assets ->
        val members = assets
            .filter { !it.groupName.isNullOrBlank() }
            .groupBy { it.groupName!!.lowercase() }

        groups.map { group ->
            BlockRow(
                id = group.id,
                name = group.name,
                notes = group.notes,
                totals = AssetGrouping.totals(
                    members[group.name.lowercase()].orEmpty().map { item ->
                        GroupableAsset(
                            status = item.due.status,
                            lengthM = item.asset.lengthM,
                            swathWidthM = item.asset.swathWidthM,
                            passesRequired = item.asset.passesRequired,
                            groundSqm = item.asset.groundSqm
                        )
                    }
                )
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    BlocksViewModel(AssetRepository(SprayDayDatabase.get(appContext)))
                }
            }
        }
    }
}
