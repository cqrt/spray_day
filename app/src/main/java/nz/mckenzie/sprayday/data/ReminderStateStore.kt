package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.reminders.ReminderState

private val Context.reminderDataStore: DataStore<Preferences> by preferencesDataStore(name = "spray_day_reminders")

/**
 * The reminder state, as text: `1789000000000|7=OVERDUE,9=DUE_SOON`.
 *
 * Kept as one compact string rather than a preference per track, so the only thing
 * that can go wrong is the whole thing being unreadable - and then the safe reading
 * is "nothing has been said yet", which at worst repeats one notification.
 */
object ReminderStateCodec {

    private const val SEPARATOR = '|'

    fun encode(state: ReminderState): String {
        val told = state.notified.entries.joinToString(",") { "${it.key}=${it.value.name}" }
        return "${state.notifiedAtEpochMs ?: ""}$SEPARATOR$told"
    }

    fun decode(text: String?): ReminderState {
        if (text.isNullOrBlank()) return ReminderState()

        val parts = text.split(SEPARATOR)
        val notifiedAt = parts.getOrNull(0)?.toLongOrNull()
        val notified = parts.getOrNull(1).orEmpty()
            .split(",")
            .mapNotNull { entry ->
                val halves = entry.split("=")
                if (halves.size != 2) return@mapNotNull null
                val assetId = halves[0].toLongOrNull() ?: return@mapNotNull null
                val status = runCatching { DueStatus.valueOf(halves[1]) }.getOrNull() ?: return@mapNotNull null
                assetId to status
            }
            .toMap()

        return ReminderState(notifiedAtEpochMs = notifiedAt, notified = notified)
    }
}

/** Where the reminder state lives between background checks. */
class ReminderStateStore(private val context: Context) {

    private val statePref = stringPreferencesKey("reminder_state")

    val state: Flow<ReminderState> =
        context.reminderDataStore.data.map { ReminderStateCodec.decode(it[statePref]) }

    suspend fun save(state: ReminderState) {
        context.reminderDataStore.edit { prefs -> prefs[statePref] = ReminderStateCodec.encode(state) }
    }
}
