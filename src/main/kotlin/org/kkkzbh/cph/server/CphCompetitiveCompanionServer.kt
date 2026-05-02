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
            s.executor = Executors.newSingleThreadExecutor { runnable ->
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
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                respond(exchange, 405, "Method Not Allowed")
                return
            }
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
        } catch (e: Throwable) {
            logger.warn("CPH server handler exception", e)
            runCatching { respond(exchange, 500, e.message ?: "internal error") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    @Throws(IOException::class)
    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
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

        fun getInstance(): CphCompetitiveCompanionServer =
            ApplicationManager.getApplication().getService(CphCompetitiveCompanionServer::class.java)
    }
}
