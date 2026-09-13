package org.kkkzbh.cph

import com.intellij.execution.BeforeRunTask
import com.intellij.openapi.util.Key
import org.junit.Assert.*
import org.junit.Test

class CphCppFilePreparationTest {
    private class BuildTask : BeforeRunTask<BuildTask>(Key.create("test.clion.build"))

    @Test
    fun preparationRunsBeforeNativeBuildAndKeepsOtherTasks() {
        val build = BuildTask()
        val tasks = CphCppFilePreparationTask.orderedTasks(listOf(build))
        assertEquals(CphCppFilePreparationTask.ID, tasks[0].providerId)
        assertTrue(tasks[0].isEnabled)
        assertSame(build, tasks[1])
    }

    @Test
    fun configurationRefreshRestoresPreparationOrderWithoutDuplicates() {
        val build = BuildTask()
        val disabled = CphCppFilePreparationTask().apply { isEnabled = false }
        val tasks = CphCppFilePreparationTask.orderedTasks(listOf(build, disabled, CphCppFilePreparationTask()))
        assertEquals(2, tasks.size)
        assertTrue(tasks[0].isEnabled)
        assertSame(build, tasks[1])
        assertEquals(tasks, CphCppFilePreparationTask.orderedTasks(tasks))
    }
}
