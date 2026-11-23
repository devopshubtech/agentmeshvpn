package io.agentmesh.control.ui

import androidx.compose.runtime.Composable
import io.agentmesh.control.admin.AdminRoot
import io.agentmesh.control.data.ConnectProfile

/** The admin app: sign in, approve phones, set limits, invite, and connect too. */
@Composable
fun RootContent(vm: AppViewModel, onScan: () -> Unit, onPickImage: () -> Unit, onConnect: (ConnectProfile) -> Unit) {
    AgentMeshTheme { AdminRoot(vm, onScan, onPickImage, onConnect) }
}
