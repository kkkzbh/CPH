package org.kkkzbh.cph.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.kkkzbh.cph.CphCodeforcesSubmitFeature
import org.kkkzbh.cph.submit.CphActiveTabService
import org.kkkzbh.cph.submit.CphSubmitBridgeUpdate
import org.kkkzbh.cph.submit.CphSubmitOrchestrator
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal sealed class CphServerStatus {
    object Stopped : CphServerStatus()
    object Disabled : CphServerStatus()
    data class Running(val port: Int) : CphServerStatus()
    data class Error(val port: Int, val message: String) : CphServerStatus()
}

@Service(Service.Level.APP)
internal class CphCompetitiveCompanionServer : Disposable {
    private val logger = Logger.getInstance(CphCompetitiveCompanionServer::class.java)
    private val lock = Any()
    private var server: HttpServer? = null

    @Volatile
    private var statusValue: CphServerStatus = CphServerStatus.Stopped

    val status: CphServerStatus get() = statusValue

    fun init() {
        synchronized(lock) {
            if (server != null) return
            startInternal()
        }
    }

    fun reload() {
        synchronized(lock) {
            stopInternal()
            startInternal()
        }
    }

    private fun startInternal() {
        val state = CphImportSettings.getInstance().state
        if (!state.enabled) {
            statusValue = CphServerStatus.Disabled
            return
        }
        val port = CphImportSettings.clampPort(state.port)
        try {
            val s = HttpServer.create(
                InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                0,
            )
            s.createContext("/", ::handleExchange)
            s.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "CPH-CC-Server-${threadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
            s.start()
            server = s
            statusValue = CphServerStatus.Running(port)
            logger.info("CPH Competitive Companion server listening on 127.0.0.1:$port")
        } catch (e: Throwable) {
            statusValue = CphServerStatus.Error(port, e.message ?: e.javaClass.simpleName)
            logger.warn("CPH Competitive Companion server failed to start on port $port", e)
        }
    }

    private fun stopInternal() {
        val current = server ?: run {
            statusValue = CphServerStatus.Stopped
            return
        }
        runCatching { current.stop(0) }.onFailure { logger.warn("Error stopping CPH server", it) }
        server = null
        statusValue = CphServerStatus.Stopped
    }

    private fun handleExchange(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                respondPreflight(exchange)
                return
            }
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                respond(exchange, 405, "Method Not Allowed")
                return
            }
            val rawPath = exchange.requestURI.path ?: "/"
            val path = rawPath.trimEnd('/').ifBlank { "/" }
            when (path) {
                CPH_ACTIVE_TAB_PATH -> handleActiveTab(exchange)
                CPH_SUBMIT_NOW_PATH -> handleSubmitNow(exchange)
                CPH_SUBMIT_POLL_PATH -> handleSubmitPoll(exchange)
                CPH_SUBMIT_UPDATE_PATH -> handleSubmitUpdate(exchange)
                else -> handleCompetitiveCompanion(exchange)
            }
        } catch (e: Throwable) {
            logger.warn("CPH server handler exception", e)
            runCatching { respond(exchange, 500, e.message ?: "internal error") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun handleCompetitiveCompanion(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readAllBytes() }
            .toString(StandardCharsets.UTF_8)
        val payload = CompetitiveCompanionParser.parse(body)
        if (payload == null) {
            logger.info("CPH server rejected payload: invalid JSON")
            respond(exchange, 400, "Invalid Competitive Companion payload")
            return
        }
        val project = resolveProject()
        if (project == null) {
            respond(exchange, 503, "No open project to receive payload")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { CphProblemImporter.getInstance(project).import(payload) }
                .onFailure { logger.warn("CPH import failed", it) }
        }
        respond(exchange, 200, "Accepted: ${payload.name}")
    }

    private fun handleActiveTab(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readAllBytes() }
            .toString(StandardCharsets.UTF_8)
        val parsed = CphActiveTabPayload.parse(body)
        if (parsed == null) {
            respond(exchange, 400, "Invalid active-tab payload")
            return
        }
        CphActiveTabService.getInstance().update(parsed.url, parsed.title)
        respond(exchange, 200, "ok")
    }

    private fun handleSubmitNow(exchange: HttpExchange) {
        if (!CphCodeforcesSubmitFeature.isEnabled()) {
            respond(exchange, 403, "Codeforces remote submit plugin is disabled")
            return
        }
        val body = exchange.requestBody.use { it.readAllBytes() }
            .toString(StandardCharsets.UTF_8)
        val parsed = CphActiveTabPayload.parse(body)
        if (parsed != null) {
            CphActiveTabService.getInstance().update(parsed.url, parsed.title)
        }
        val project = resolveProject()
        if (project == null) {
            respond(exchange, 503, "No open project")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { CphSubmitOrchestrator.getInstance(project).submit() }
                .onFailure { logger.warn("CPH submit-now failed", it) }
        }
        respond(exchange, 202, "submitting")
    }

    private fun handleSubmitPoll(exchange: HttpExchange) {
        if (!CphCodeforcesSubmitFeature.isEnabled()) {
            respond(exchange, 403, "Codeforces remote submit plugin is disabled")
            return
        }
        val body = exchange.requestBody.use { it.readAllBytes() }
            .toString(StandardCharsets.UTF_8)
        val parsed = CphActiveTabPayload.parse(body)
        if (parsed == null) {
            respond(exchange, 400, "Invalid submit-poll payload")
            return
        }
        CphActiveTabService.getInstance().update(parsed.url, parsed.title)
        val project = resolveProject()
        if (project == null) {
            respondNoContent(exchange)
            return
        }
        val orchestrator = CphSubmitOrchestrator.getInstance(project)
        val deadline = System.currentTimeMillis() + SUBMIT_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val tab = CphActiveTabService.getInstance().lastEver()
            val job = tab?.let { orchestrator.pollSubmitJob(it) }
            if (job != null) {
                respondJson(exchange, 200, job.toJson())
                return
            }
            Thread.sleep(SUBMIT_POLL_INTERVAL_MS)
        }
        respondNoContent(exchange)
    }

    private fun handleSubmitUpdate(exchange: HttpExchange) {
        if (!CphCodeforcesSubmitFeature.isEnabled()) {
            respond(exchange, 403, "Codeforces remote submit plugin is disabled")
            return
        }
        val body = exchange.requestBody.use { it.readAllBytes() }
            .toString(StandardCharsets.UTF_8)
        val update = CphSubmitBridgeUpdate.parse(body)
        if (update == null) {
            respond(exchange, 400, "Invalid submit-update payload")
            return
        }
        val project = resolveProject()
        if (project == null) {
            respond(exchange, 503, "No open project")
            return
        }
        val accepted = CphSubmitOrchestrator.getInstance(project).applyBridgeUpdate(update)
        if (accepted) {
            respond(exchange, 200, "ok")
        } else {
            respond(exchange, 409, "Unknown or stale submit job")
        }
    }

    @Throws(IOException::class)
    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
        addCorsHeaders(exchange)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Throws(IOException::class)
    private fun respondJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        addCorsHeaders(exchange)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Throws(IOException::class)
    private fun respondNoContent(exchange: HttpExchange) {
        addCorsHeaders(exchange)
        exchange.sendResponseHeaders(204, -1)
    }

    @Throws(IOException::class)
    private fun respondPreflight(exchange: HttpExchange) {
        addCorsHeaders(exchange)
        exchange.sendResponseHeaders(204, -1)
    }

    private fun addCorsHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "POST, OPTIONS")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type")
        exchange.responseHeaders.set("Access-Control-Allow-Private-Network", "true")
    }

    private fun resolveProject(): Project? {
        val openProjects = ProjectManager.getInstance().openProjects
        if (openProjects.isEmpty()) return null
        val focused = runCatching {
            IdeFocusManager.findInstance().lastFocusedFrame?.project
        }.getOrNull()
        return focused?.takeIf { !it.isDisposed && it in openProjects }
            ?: openProjects.firstOrNull { !it.isDisposed }
    }

    override fun dispose() {
        synchronized(lock) { stopInternal() }
    }

    companion object {
        private val threadCounter = AtomicLong()
        const val CPH_ACTIVE_TAB_PATH = "/cph/active-tab"
        const val CPH_SUBMIT_NOW_PATH = "/cph/submit-now"
        const val CPH_SUBMIT_POLL_PATH = "/cph/submit/poll"
        const val CPH_SUBMIT_UPDATE_PATH = "/cph/submit/update"
        private const val SUBMIT_POLL_TIMEOUT_MS = 20_000L
        private const val SUBMIT_POLL_INTERVAL_MS = 300L

        fun getInstance(): CphCompetitiveCompanionServer =
            ApplicationManager.getApplication().getService(CphCompetitiveCompanionServer::class.java)
    }
}
