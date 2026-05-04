package org.kkkzbh.cph.submit

import com.intellij.util.messages.Topic

internal enum class CphSubmissionPhase {
    IDLE,
    SUBMITTING,
    QUEUED,
    RUNNING,
    ACCEPTED,
    REJECTED,
    ERROR,
}

internal data class CphSubmissionStatus(
    val phase: CphSubmissionPhase,
    val displayId: String,
    val text: String,
    val submissionId: Long? = null,
    val pageUrl: String? = null,
    val errorDetail: String? = null,
) {
    companion object {
        fun idle(): CphSubmissionStatus =
            CphSubmissionStatus(CphSubmissionPhase.IDLE, "", "")
    }
}

internal interface CphSubmissionStatusListener {
    fun submissionStatusChanged(status: CphSubmissionStatus)

    companion object {
        val TOPIC: Topic<CphSubmissionStatusListener> =
            Topic.create("CPH submission status changed", CphSubmissionStatusListener::class.java)
    }
}
