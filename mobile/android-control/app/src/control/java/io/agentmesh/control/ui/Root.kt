package io.agentmesh.control.ui

import androidx.compose.runtime.Composable
import io.agentmesh.control.data.ConnectProfile

/** The normal phone app: just the connect screen. (The admin flavor has its own Root.kt.) */
@Composable
fun RootContent(vm: AppViewModel, onScan: () -> Unit, onPickImage: () -> Unit, onConnect: (ConnectProfile) -> Unit) {
    AgentMeshApp(vm, onScan, onPickImage, onConnect)
}
