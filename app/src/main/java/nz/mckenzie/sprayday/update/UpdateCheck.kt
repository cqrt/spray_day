package nz.mckenzie.sprayday.update

import kotlinx.coroutines.flow.first
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.domain.update.AppVersion
import nz.mckenzie.sprayday.domain.update.AvailableUpdate
import nz.mckenzie.sprayday.domain.update.ReleaseCatalog

/**
 * What one update check found, in words worth putting on the settings screen.
 *
 * [notified] is false when there was something to say and nothing was said - because the
 * operator has already been told about this version, or because notifications are turned
 * off for the app. Those are different sentences on the screen, so the reason is in
 * [message] rather than inferred.
 */
data class UpdateOutcome(
    val available: AvailableUpdate?,
    val notified: Boolean,
    val message: String
)

/**
 * One pass of "is there a newer version, and should I say so?".
 *
 * Shared by the background worker and the settings screen's Check now button, so the
 * button reports exactly what the background job would have done - the arrangement the due
 * reminders already use, and the reason there is one of these rather than two.
 *
 * Nothing is remembered about a version that was not actually announced: turning
 * notifications on later should not find that the news was already spent, which is the
 * same rule the reminders follow.
 */
class UpdateCheck(
    private val current: AppVersion?,
    private val supportedAbis: List<String>,
    private val feed: ReleaseFeed,
    /** The version the operator has already been told about, if any. */
    private val lastNotified: suspend () -> String,
    private val rememberNotified: suspend (String) -> Unit,
    /** Posts the notification and says whether it actually appeared. */
    private val post: (title: String, body: String) -> Boolean
) {

    /**
     * The same pass, wired to the app's own settings and notification.
     *
     * Both the background worker and the settings screen build it this way, so the button
     * and the job cannot drift apart in what they remember or what they announce.
     */
    constructor(
        current: AppVersion?,
        supportedAbis: List<String>,
        feed: ReleaseFeed,
        settings: SettingsRepository,
        notifier: UpdateNotifier
    ) : this(
        current,
        supportedAbis,
        feed,
        { settings.lastNotifiedUpdate.first() },
        { version -> settings.setLastNotifiedUpdate(version) },
        notifier::notify
    )

    suspend fun run(announce: Boolean = true): UpdateOutcome {
        val version = current
            ?: return UpdateOutcome(
                available = null,
                notified = false,
                message = "This build does not say what version it is, so there is nothing to compare."
            )

        return when (val answer = feed.latest()) {
            is FeedResult.Failed -> UpdateOutcome(null, false, answer.message)

            is FeedResult.Answer -> {
                val update = ReleaseCatalog.updateFor(answer.release, version, supportedAbis)
                when {
                    answer.release == null ->
                        UpdateOutcome(null, false, "No releases have been published yet.")

                    update == null ->
                        UpdateOutcome(null, false, "You are on the newest version ($version).")

                    // Asked for from the settings screen, where the answer is already on
                    // the screen: posting a notification about the very thing the operator
                    // is looking at is noise, and saying nothing was announced because
                    // notifications are off would be a lie.
                    !announce ->
                        UpdateOutcome(
                            available = update,
                            notified = false,
                            message = "${update.title} is available."
                        )

                    lastNotified() == update.version.toString() ->
                        UpdateOutcome(
                            available = update,
                            notified = false,
                            message = "${update.title} is available, and you have already been told."
                        )

                    else -> {
                        val posted = post(
                            "${update.title} is available",
                            "Open Spray Day and update from Settings. It is a " +
                                "${update.sizeLabel} download, and your records are kept."
                        )
                        if (posted) rememberNotified(update.version.toString())
                        UpdateOutcome(
                            available = update,
                            notified = posted,
                            message = if (posted) {
                                "${update.title} is available. Told you about it; open Settings to update."
                            } else {
                                "${update.title} is available, but notifications are turned " +
                                    "off for Spray Day."
                            }
                        )
                    }
                }
            }
        }
    }
}
