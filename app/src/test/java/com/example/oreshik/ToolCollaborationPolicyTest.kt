package com.example.oreshik

import com.example.oreshik.agent.core.ToolCollaborationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCollaborationPolicyTest {
    @Test
    fun workflowDelegationAllowsReadOnlyInspectionTools() {
        listOf(
            "file.list", "file.read", "file.search",
            "git.status", "git.diff", "git.log",
            "runtime.info", "skills.list", "memory.stats",
        ).forEach { assertTrue("$it should be delegatable", ToolCollaborationPolicy.canDelegate(it)) }
    }

    @Test
    fun workflowDelegationRejectsMutatingAndDeviceActionTools() {
        listOf(
            "file.write", "file.delete", "git.remote", "python.exec",
            "workspace.rollback", "accessibility.click", "accessibility.sequence",
            "root.exec", "mem.write",
        ).forEach { assertFalse("$it must not be delegatable", ToolCollaborationPolicy.canDelegate(it)) }
    }
}
