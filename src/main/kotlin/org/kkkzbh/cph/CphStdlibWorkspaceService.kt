package org.kkkzbh.cph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.jetbrains.cidr.lang.OCLanguageKind
import com.jetbrains.cidr.lang.workspace.OCWorkspace
import com.jetbrains.cidr.lang.workspace.OCWorkspaceListener
import com.jetbrains.cidr.lang.workspace.moduleRoots.ModuleSearchPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Supplies standard-library module sources to the native single-file project model used by Nova. */
internal class CphStdlibWorkspaceService(private val project: Project, scope: CoroutineScope) {
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val sources = linkedMapOf<String, List<ModuleSearchPath>>()
    private val owned = mutableMapOf<Pair<String, String>, List<ModuleSearchPath>>()

    init {
        project.messageBus.connect(scope).subscribe(OCWorkspaceListener.TOPIC, object : OCWorkspaceListener {
            override fun workspaceChanged(event: OCWorkspaceListener.OCWorkspaceEvent) {
                updates.trySend(Unit)
            }
        })
        scope.launch {
            for (ignored in updates) mutex.withLock { apply() }
        }
    }

    fun update(source: File, modules: List<File>) = runBlocking {
        mutex.withLock {
            sources[VfsUtilCore.pathToUrl(source.invariantSeparatorsPath)] = modules.map { ModuleSearchPath(it.toPath()) }
            apply()
        }
    }

    private fun apply() {
        val workspace = OCWorkspace.getInstance(project)
        while (true) {
            val nextOwned = mutableMapOf<Pair<String, String>, List<ModuleSearchPath>>()
            val transaction = ReadAction.compute<Transaction?, RuntimeException> {
                val changes = workspace.getConfigurations("SINGLE_FILE").flatMap { configuration ->
                    sources.mapNotNull { (url, desired) ->
                        if (url !in configuration.sourceUrls) return@mapNotNull null
                        val kind = checkNotNull(configuration.getDeclaredLanguageKind(url))
                        // Native single-file configurations store compiler settings at language scope.
                        // A module-only source override is discarded when CLion normalizes the model.
                        val current = configuration.getCompilerSettings(kind).moduleSearchPaths
                        val key = configuration.uniqueId to url
                        val base = current - owned[key].orEmpty().toSet()
                        val next = (base + desired).distinct()
                        nextOwned[key] = desired - base.toSet()
                        if (current == next) null else Change(configuration.uniqueId, kind, next)
                    }
                }
                if (changes.isEmpty()) null else {
                    val model = workspace.getModifiableModel("SINGLE_FILE", false)
                    changes.forEach { change ->
                        checkNotNull(model.getConfigurationById(change.id)).getLanguageCompilerSettings(change.kind)
                            .setModuleSearchPaths(change.paths)
                    }
                    Transaction(model, revision(workspace))
                }
            }
            if (transaction == null) {
                owned.clear()
                owned.putAll(nextOwned)
                return
            }
            var committed = false
            try {
                transaction.model.preCommit()
                ApplicationManager.getApplication().invokeAndWait {
                    WriteAction.run<RuntimeException> {
                        // Native rebuilds can finish while preCommit runs. Commit only against the
                        // snapshot we prepared, so a module update cannot restore stale compiler settings.
                        if (transaction.revision == revision(workspace)) {
                            transaction.model.commit()
                            committed = true
                        }
                    }
                }
            } finally {
                Disposer.dispose(transaction.model)
            }
            if (committed) {
                owned.clear()
                owned.putAll(nextOwned)
                return
            }
        }
    }

    private fun revision(workspace: OCWorkspace) = workspace.modificationTrackers.let {
        listOf(it.resolveConfigurationsTracker.modificationCount, it.sourceFilesTracker.modificationCount,
            it.compilerSettingsTracker.modificationCount, it.clientVersionTracker.modificationCount)
    }

    private data class Transaction(val model: OCWorkspace.ModifiableModel, val revision: List<Long>)
    private data class Change(val id: String, val kind: OCLanguageKind, val paths: List<ModuleSearchPath>)
}
