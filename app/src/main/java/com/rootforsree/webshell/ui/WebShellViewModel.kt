package com.rootforsree.webshell.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rootforsree.webshell.server.ServerHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One screen, one job: start and stop the server, show the links.
 * All server state comes from the [ServerHost] flows; the ViewModel
 * only maps them into one immutable [WebShellUiState] snapshot.
 */
class WebShellViewModel(
    private val host: ServerHost,
) : ViewModel() {

    val uiState: StateFlow<WebShellUiState> field = MutableStateFlow(WebShellUiState())

    init {
        viewModelScope.launch {
            combine(
                host.isRunning,
                host.sessionInfo,
                host.logLines,
                host.error,
            ) { isRunning, info, log, error ->
                WebShellUiState.from(
                    info = info,
                    isRunning = isRunning,
                    log = log,
                    error = error,
                    hasAllFilesAccess = host.hasAllFilesAccess(),
                )
            }.collect { uiState.value = it }
        }
    }

    fun onStartClicked() {
        host.start()
    }

    fun onStopClicked() {
        host.stop()
    }

    fun onDismissError() {
        host.clearError()
    }

    fun onBatteryExemptionClicked() {
        host.requestBatteryExemption()
    }

    fun onAllFilesAccessClicked() {
        host.requestAllFilesAccess()
    }
}
