package nz.mckenzie.sprayday.domain.recording

/** Lifecycle of a GPS recording session. Persisted by name. */
enum class RecordingStatus {
    RECORDING,
    PAUSED,
    FINISHED
}
