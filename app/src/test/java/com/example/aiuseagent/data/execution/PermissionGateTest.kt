package com.example.aiuseagent.data.execution

import org.junit.Assert.*
import org.junit.Test

class PermissionGateTest {
    @Test fun permissionMatrixNeverLetsFullBypassProtectionGrantOrIsolation() {
        for(level in PermissionLevel.entries) {
            assertEquals(GateDecision.DENY,PermissionGate.decide(level,OperationFacts(Effect.READ,protectedResource=true)))
            assertEquals(GateDecision.SYSTEM_GRANT_REQUIRED,PermissionGate.decide(level,OperationFacts(Effect.READ,androidGranted=false)))
            assertEquals(GateDecision.DENY,PermissionGate.decide(level,OperationFacts(Effect.WRITE,writable=false)))
            assertEquals(GateDecision.UNSUPPORTED,PermissionGate.decide(level,OperationFacts(Effect.COMMAND_WRITE,isolated=false)))
            assertEquals(GateDecision.ALLOW,PermissionGate.decide(level,OperationFacts(Effect.READ)))
            assertEquals(GateDecision.ALLOW,PermissionGate.decide(level,OperationFacts(Effect.COMMAND_READ,isolated=true)))
        }
        for(effect in listOf(Effect.WRITE,Effect.DELETE,Effect.COMMAND_WRITE)) {
            val facts=OperationFacts(effect,isolated=true)
            assertEquals(GateDecision.APPROVAL_REQUIRED,PermissionGate.decide(PermissionLevel.VIEW_ONLY,facts))
            assertEquals(GateDecision.ALLOW,PermissionGate.decide(PermissionLevel.WORKSPACE_EDIT,facts))
            assertEquals(GateDecision.ALLOW,PermissionGate.decide(PermissionLevel.FULL,facts))
        }
        for(level in listOf(PermissionLevel.VIEW_ONLY,PermissionLevel.WORKSPACE_EDIT)) {
            assertEquals(GateDecision.APPROVAL_REQUIRED,PermissionGate.decide(level,OperationFacts(Effect.READ,currentWorkspace=false)))
            assertEquals(GateDecision.APPROVAL_REQUIRED,PermissionGate.decide(level,OperationFacts(Effect.COMMAND_READ,isolated=true,network=true)))
        }
        assertEquals(GateDecision.ALLOW,PermissionGate.decide(PermissionLevel.FULL,OperationFacts(Effect.READ,currentWorkspace=false)))
    }
}
