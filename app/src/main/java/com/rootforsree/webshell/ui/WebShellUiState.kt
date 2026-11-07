package com.rootforsree.webshell.ui

import com.rootforsree.webshell.server.LanAddress
import com.rootforsree.webshell.server.ServerInfo
import com.rootforsree.webshell.server.buildShellUrl
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** One URL row on the screen: its label and the full link to open. */
data class UrlRow(
    val label: String,
    val url: String,
)

/** The whole screen, as one immutable snapshot. */
data class WebShellUiState(
    val isRunning: Boolean = false,
    val urls: ImmutableList<UrlRow> = persistentListOf(),
    val token: String = "",
    val log: ImmutableList<String> = persistentListOf(),
    val error: String? = null,
    val hasAllFilesAccess: Boolean = false,
) {
    companion object {
        fun from(info: ServerInfo?, isRunning: Boolean, log: List<String>, error: String?, hasAllFilesAccess: Boolean): WebShellUiState {
            val urls = info?.addresses?.map { address: LanAddress ->
                UrlRow(address.label, buildShellUrl(address, info.port, info.token))
            }.orEmpty()
            return WebShellUiState(
                isRunning = isRunning,
                urls = urls.toImmutableList(),
                token = info?.token.orEmpty(),
                log = log.toImmutableList(),
                error = error,
                hasAllFilesAccess = hasAllFilesAccess,
            )
        }
    }
}
