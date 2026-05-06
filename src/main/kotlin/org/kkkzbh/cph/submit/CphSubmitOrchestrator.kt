package org.kkkzbh.cph.submit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.kkkzbh.cph.CphCodeforcesSubmitFeature
import org.kkkzbh.cph.CphStateService
import java.util.UUID

internal data class CphSubmitBridgeJob(
    val jobId: String,
    val context: CphSubmitContext,
    val programTypeId: Int,
    val source: String,
) {
    fun toJson(): String {
        val obj = JsonObject()
        obj.addProperty("jobId", jobId)
        obj.addProperty("problemUrl", context.problemPageUrl)
        obj.addProperty("submitPageUrl", context.submitPageUrl)
        obj.addProperty("kind", context.kind.name)
        obj.addProperty("contestId", context.contestId)
        obj.addProperty("problemIndex", context.problemIndex)
        obj.addProperty("displayId", context.displayId)
        obj.addProperty("programTypeId", programTypeId)
        obj.addProperty("source", source)
        return obj.toString()
    }
}

internal data class CphSubmitBridgeUpdate(
    val jobId: String,
    val phase: CphSubmissionPhase,
    val text: String,
    val submissionId: Long?,
    val pageUrl: String?,
    val errorDetail: String?,
) {
    companion object {
        fun parse(body: String): CphSubmitBridgeUpdate? {
            if (body.isBlank()) return null
            return try {
                val obj = JsonParser.parseString(body).asJsonObject
                val jobId = obj.string("jobId").orEmpty()
                val phaseName = obj.string("phase").orEmpty()
                val phase = runCatching { CphSubmissionPhase.valueOf(phaseName) }.getOrNull()
                if (jobId.isBlank() || phase == null) return null
                CphSubmitBridgeUpdate(
                    jobId = jobId,
                    phase = phase,
                    text = obj.string("text").orEmpty(),
                    submissionId = obj.long("submissionId"),
                    pageUrl = obj.string("pageUrl")?.takeIf { it.isNotBlank() },
                    errorDetail = obj.string("errorDetail")?.takeIf { it.isNotBlank() },
                )
            } catch (_: JsonSyntaxException) {
                null
            } catch (_: IllegalStateException) {
                null
            } catch (_: ClassCastException) {
                null
            }
        }

        private fun JsonObject.string(name: String): String? =
            get(name)?.takeIf { !it.isJsonNull }?.asString

        private fun JsonObject.long(name: String): Long? =
            get(name)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() }
    }
}

@Service(Service.Level.PROJECT)
internal class CphSubmitOrchestrator(private val project: Project) {
    private val logger = Logger.getInstance(CphSubmitOrchestrator::class.java)
    private val lock = Any()
    private var pendingJob: CphSubmitBridgeJob? = null
    private var activeJob: CphSubmitBridgeJob? = null

    fun submit() {
        if (!CphCodeforcesSubmitFeature.isEnabled()) {
            publishIdle()
            return
        }
        synchronized(lock) {
            if (activeJob != null || pendingJob != null) {
                balloon("A submission is already in flight.", NotificationType.WARNING)
                return
            }
        }

        try {
            if (!CphStateService.getInstance(project).state.singleFileModeEnabled) {
                warnAndIdle("Codeforces submit requires pure single-file mode. Enable pure single-file mode in CPH settings first.")
                return
            }
            val tab = CphActiveTabService.getInstance().current()
            if (tab == null) {
                warnAndIdle("No active Codeforces tab — install/open the CPH Target Runner browser extension and focus a CF problem page.")
                return
            }
            val ctx = CphSubmitContextResolver.resolve(tab.url)
            if (ctx == null) {
                warnAndIdle("Active tab is not a Codeforces problem page: ${tab.url}")
                return
            }
            val sourceFile = currentSourceFile()
            if (sourceFile == null) {
                warnAndIdle("No editor is open. Switch to a .cpp/.cc file and click Submit again.")
                return
            }
            if (!isSupportedSource(sourceFile)) {
                warnAndIdle(
                    "Only C++ files (.cpp/.cc/.cxx/.cp) are supported by CPH submit (got .${sourceFile.extension ?: "?"}).",
                )
                return
            }

            val source = readSource(sourceFile)
            val job = CphSubmitBridgeJob(
                jobId = UUID.randomUUID().toString(),
                context = ctx,
                programTypeId = submitLanguage().defaultProgramTypeId,
                source = source,
            )
            synchronized(lock) {
                pendingJob = job
                activeJob = job
            }
            publish(
                CphSubmissionStatus(
                    phase = CphSubmissionPhase.SUBMITTING,
                    displayId = ctx.displayId,
                    text = "→ ${ctx.displayId}  Waiting for CPH Target Runner browser extension…",
                ),
            )
            startTimeoutWatch(job)
        } catch (e: Throwable) {
            fail(null, e.message ?: e.javaClass.simpleName, e)
        }
    }

    fun pollSubmitJob(tab: CphActiveTab): CphSubmitBridgeJob? {
        if (!CphCodeforcesSubmitFeature.isEnabled()) return null
        val ctx = CphSubmitContextResolver.resolve(tab.url) ?: return null
        val job = synchronized(lock) {
            val pending = pendingJob ?: return@synchronized null
            if (!sameProblem(pending.context, ctx)) return@synchronized null
            pending
        } ?: return null
        publish(
            CphSubmissionStatus(
                phase = CphSubmissionPhase.SUBMITTING,
                displayId = job.context.displayId,
                text = "→ ${job.context.displayId}  Browser received source…",
            ),
        )
        return job
    }

    fun applyBridgeUpdate(update: CphSubmitBridgeUpdate): Boolean {
        if (!CphCodeforcesSubmitFeature.isEnabled()) return false
        val job = synchronized(lock) {
            val active = activeJob ?: return false
            if (active.jobId != update.jobId) return false
            pendingJob = null
            if (update.phase.isTerminal()) {
                activeJob = null
            }
            active
        }
        val text = update.text.ifBlank {
            if (update.phase == CphSubmissionPhase.ERROR) "Failed: ${update.errorDetail ?: "unknown error"}" else update.phase.name
        }
        publish(
            CphSubmissionStatus(
                phase = update.phase,
                displayId = job.context.displayId,
                text = text,
                submissionId = update.submissionId,
                pageUrl = update.pageUrl,
                errorDetail = update.errorDetail,
            ),
        )
        if (update.phase.isTerminal()) {
            val type = when (update.phase) {
                CphSubmissionPhase.ACCEPTED -> NotificationType.INFORMATION
                CphSubmissionPhase.ERROR -> NotificationType.ERROR
                else -> NotificationType.WARNING
            }
            balloon("[CF ${job.context.displayId}] $text", type)
        }
        return true
    }

    private fun startTimeoutWatch(job: CphSubmitBridgeJob) {
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(BRIDGE_PICKUP_TIMEOUT_MS)
            val expiredBeforePickup = synchronized(lock) {
                val expired = pendingJob?.jobId == job.jobId
                if (expired) {
                    pendingJob = null
                    activeJob = null
                }
                expired
            }
            if (expiredBeforePickup) {
                fail(job.context.displayId, "CPH Target Runner browser extension did not pick up the submit request. Focus the CF problem tab and check the extension.", null)
                return@executeOnPooledThread
            }

            Thread.sleep(SUBMISSION_TIMEOUT_MS - BRIDGE_PICKUP_TIMEOUT_MS)
            val expiredAfterPickup = synchronized(lock) {
                val expired = activeJob?.jobId == job.jobId
                if (expired) {
                    activeJob = null
                    pendingJob = null
                }
                expired
            }
            if (expiredAfterPickup) {
                fail(job.context.displayId, "Submission timed out before a final Codeforces verdict.", null)
            }
        }
    }

    private fun sameProblem(left: CphSubmitContext, right: CphSubmitContext): Boolean =
        left.kind == right.kind &&
            left.contestId == right.contestId &&
            left.problemIndex.equals(right.problemIndex, ignoreCase = true)

    private fun warnAndIdle(message: String) {
        balloon(message, NotificationType.WARNING)
        publishIdle()
    }

    private fun fail(displayId: String?, message: String, cause: Throwable?) {
        if (cause != null) {
            logger.warn("CPH submit failed: $message", cause)
        } else {
            logger.warn("CPH submit failed: $message")
        }
        publish(
            CphSubmissionStatus(
                phase = CphSubmissionPhase.ERROR,
                displayId = displayId.orEmpty(),
                text = "Failed: $message",
                errorDetail = message,
            ),
        )
        balloon("[CF submit] $message", NotificationType.ERROR)
    }

    private fun publishIdle() = publish(CphSubmissionStatus.idle())

    private fun publish(status: CphSubmissionStatus) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(CphSubmissionStatusListener.TOPIC)
            .submissionStatusChanged(status)
    }

    private fun balloon(message: String, type: NotificationType) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CPH Submit")
                .createNotification(message, type)
                .notify(project)
        }
    }

    private fun currentSourceFile(): VirtualFile? =
        runReadActionBlocking { FileEditorManager.getInstance(project).selectedFiles.firstOrNull() }

    private fun readSource(file: VirtualFile): String =
        runReadActionBlocking {
            val doc = FileDocumentManager.getInstance().getDocument(file)
            doc?.text ?: String(file.contentsToByteArray(), Charsets.UTF_8)
        }

    private fun isSupportedSource(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext in CPP_EXTS
    }

    private fun submitLanguage(): CphCfLanguage {
        val standard = CphStateService.getInstance(project).state.compileSettings.cppStandard
        return CphCfLanguage.fromCppStandard(standard)
    }

    private fun CphSubmissionPhase.isTerminal(): Boolean =
        this == CphSubmissionPhase.ACCEPTED ||
            this == CphSubmissionPhase.REJECTED ||
            this == CphSubmissionPhase.ERROR

    companion object {
        private val CPP_EXTS = setOf("cpp", "cc", "cxx", "cp", "c++", "h", "hpp")
        private const val BRIDGE_PICKUP_TIMEOUT_MS = 15_000L
        private const val SUBMISSION_TIMEOUT_MS = 180_000L

        fun getInstance(project: Project): CphSubmitOrchestrator =
            project.getService(CphSubmitOrchestrator::class.java)
    }
}
