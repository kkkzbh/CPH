package org.kkkzbh.cph

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

internal const val CPH_TOOL_WINDOW_ID = "CPH"

internal class CphShortcutDispatcher(private val project: Project) : Disposable {
    private val dispatcher = object : IdeEventQueue.NonLockedEventDispatcher {
        override fun dispatch(e: AWTEvent): Boolean = this@CphShortcutDispatcher.dispatch(e)
    }

    init {
        IdeEventQueue.getInstance().addDispatcher(dispatcher, this)
    }

    private fun dispatch(event: AWTEvent): Boolean {
        if (project.isDisposed || event !is KeyEvent || event.isConsumed) return false
        if (CphShortcutCaptureState.handleKeyEvent(event)) return true
        if (event.id != KeyEvent.KEY_PRESSED) return false
        val component = event.component ?: return false
        if (isShortcutInputActive(component)) return false
        if (projectFrom(component) !== project) return false

        val action = CphShortcutMatcher.actionFor(
            keyStroke = CphShortcutMatcher.keyStrokeFromEvent(event),
            state = CphShortcutSettings.getInstance().state,
            fromShortcutInput = false,
            codeforcesSubmitEnabled = CphCodeforcesSubmitFeature.isEnabled(),
        ) ?: return false

        event.consume()
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                CphShortcutExecutor.execute(project, action)
            }
        }
        return true
    }

    override fun dispose() = Unit

    private fun projectFrom(component: Component): Project? {
        return CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(component))
    }

    private fun isShortcutInputActive(component: Component): Boolean {
        return isShortcutInput(component) ||
            KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let(::isShortcutInput) == true
    }

    private fun isShortcutInput(component: Component): Boolean {
        return SwingUtilities.getAncestorOfClass(CphShortcutTextField::class.java, component) != null ||
            component is CphShortcutTextField
    }

    companion object {
        fun getInstance(project: Project): CphShortcutDispatcher = project.service()
    }
}

internal object CphShortcutExecutor {
    fun execute(project: Project, action: CphShortcutAction) {
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) execute(project, action)
            }
            return
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(CPH_TOOL_WINDOW_ID) ?: return
        val panel = findCreatedPanel(toolWindow)
        if (panel != null) {
            panel.triggerShortcut(action)
            return
        }
        toolWindow.activate(
            Runnable {
                runWithPanel(toolWindow, action)
            },
            true,
        )
    }

    private fun runWithPanel(toolWindow: ToolWindow, action: CphShortcutAction) {
        val panel = findPanel(toolWindow)
        if (panel != null) {
            panel.triggerShortcut(action)
            return
        }
        ApplicationManager.getApplication().invokeLater {
            findPanel(toolWindow)?.triggerShortcut(action)
        }
    }

    private fun findCreatedPanel(toolWindow: ToolWindow): CphToolWindowPanel? {
        return toolWindow.contentManagerIfCreated?.contents
            ?.asSequence()
            ?.mapNotNull { findPanel(it.component) }
            ?.firstOrNull()
    }

    private fun findPanel(toolWindow: ToolWindow): CphToolWindowPanel? {
        return toolWindow.contentManager.contents
            .asSequence()
            .mapNotNull { findPanel(it.component) }
            .firstOrNull()
    }

    private fun findPanel(component: Component): CphToolWindowPanel? {
        if (component is CphToolWindowPanel) return component
        if (component !is Container) return null
        return component.components.asSequence().mapNotNull(::findPanel).firstOrNull()
    }
}
