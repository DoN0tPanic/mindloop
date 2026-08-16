package com.local.spacedcards.data.lan

import android.content.Context

data class GenerationJob(
    val id: String,
    val host: String,
    val port: Int,
    val code: String,
    val raccoltaUid: String,
    val raccoltaName: String,
    val startedAt: Long,
    val progress: Float = 0f,
    val stage: String? = null,
    val message: String? = null,
    val pollingActive: Boolean = false,
)

/** Durable hand-off between the Compose screen and the foreground worker. */
class GenerationJobStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(): GenerationJob? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        return GenerationJob(
            id = id,
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, DEFAULT_LAN_PORT),
            code = prefs.getString(KEY_CODE, "").orEmpty(),
            raccoltaUid = prefs.getString(KEY_COLLECTION_UID, "").orEmpty(),
            raccoltaName = prefs.getString(KEY_COLLECTION_NAME, "").orEmpty(),
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
            progress = prefs.getFloat(KEY_PROGRESS, 0f),
            stage = prefs.getString(KEY_STAGE, null),
            message = prefs.getString(KEY_MESSAGE, null),
            pollingActive = prefs.getBoolean(KEY_POLLING_ACTIVE, false),
        )
    }

    fun save(job: GenerationJob) {
        prefs.edit().putString(KEY_ID, job.id).putString(KEY_HOST, job.host).putInt(KEY_PORT, job.port)
            .putString(KEY_CODE, job.code).putString(KEY_COLLECTION_UID, job.raccoltaUid)
            .putString(KEY_COLLECTION_NAME, job.raccoltaName).putLong(KEY_STARTED_AT, job.startedAt)
            .putFloat(KEY_PROGRESS, job.progress).putString(KEY_STAGE, job.stage)
            .putString(KEY_MESSAGE, job.message).apply()
    }

    fun updateStatus(progress: Float, stage: String?, message: String?) {
        prefs.edit().putFloat(KEY_PROGRESS, progress).putString(KEY_STAGE, stage)
            .putString(KEY_MESSAGE, message).apply()
    }

    fun updatePollingActive(active: Boolean) = prefs.edit().putBoolean(KEY_POLLING_ACTIVE, active).apply()

    fun isImported(jobId: String): Boolean = prefs.getBoolean("imported_$jobId", false)
    fun markImported(jobId: String) = prefs.edit().putBoolean("imported_$jobId", true).apply()
    fun clearCurrent(jobId: String) {
        if (prefs.getString(KEY_ID, null) == jobId) {
            prefs.edit().remove(KEY_ID).remove(KEY_HOST).remove(KEY_PORT).remove(KEY_CODE)
                .remove(KEY_COLLECTION_UID).remove(KEY_COLLECTION_NAME).remove(KEY_STARTED_AT)
                .remove(KEY_PROGRESS).remove(KEY_STAGE).remove(KEY_MESSAGE).remove(KEY_POLLING_ACTIVE).apply()
        }
    }

    fun clearAsMissing(job: GenerationJob) {
        clearCurrent(job.id)
        prefs.edit().putString(KEY_MISSING_COLLECTION_UID, job.raccoltaUid).apply()
    }

    fun consumeMissingJob(raccoltaUid: String): Boolean {
        if (prefs.getString(KEY_MISSING_COLLECTION_UID, null) != raccoltaUid) return false
        prefs.edit().remove(KEY_MISSING_COLLECTION_UID).apply()
        return true
    }

    private companion object {
        const val PREFS_NAME = "lan_quiz_settings"
        const val KEY_ID = "generation_job_id"
        const val KEY_HOST = "generation_job_host"
        const val KEY_PORT = "generation_job_port"
        const val KEY_CODE = "generation_job_code"
        const val KEY_COLLECTION_UID = "generation_job_collection_uid"
        const val KEY_COLLECTION_NAME = "generation_job_collection_name"
        const val KEY_STARTED_AT = "generation_job_started_at"
        const val KEY_PROGRESS = "generation_job_progress"
        const val KEY_STAGE = "generation_job_stage"
        const val KEY_MESSAGE = "generation_job_message"
        const val KEY_POLLING_ACTIVE = "generation_job_polling_active"
        const val KEY_MISSING_COLLECTION_UID = "generation_missing_job_collection_uid"
    }
}
