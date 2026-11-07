package com.rootforsree.webshell.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rootforsree.webshell.ui.theme.WebShellGreen
import com.rootforsree.webshell.ui.theme.WebShellRed

/** Stateful route: owns the ViewModel and hands state to the content. */
@Composable
fun WebShellRoute(viewModel: WebShellViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WebShellContent(
        state = state,
        onStart = viewModel::onStartClicked,
        onStop = viewModel::onStopClicked,
        onDismissError = viewModel::onDismissError,
        onBatteryExemption = viewModel::onBatteryExemptionClicked,
        onAllFilesAccess = viewModel::onAllFilesAccessClicked,
    )
}

/** Stateless content: state in, events out. */
@Composable
fun WebShellContent(
    state: WebShellUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    onBatteryExemption: () -> Unit,
    onAllFilesAccess: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result does not gate the server; the notification is suppressed when denied. */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "WebShell",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "Start the server, open the link in any browser, get a shell on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = if (state.isRunning) "Running" else "Stopped",
            color = if (state.isRunning) WebShellGreen else WebShellRed,
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.isRunning) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text("Stop server")
            }
        } else {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    onStart()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text("Start server")
            }
        }

        state.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDismissError) {
                        Text("Dismiss")
                    }
                }
            }
        }

        if (state.isRunning) {
            Text(text = "Open one of these links in any browser:", style = MaterialTheme.typography.titleMedium)
            for (url in state.urls) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = url.label, style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = url.url,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(url.url)) },
                        ) {
                            Text("Copy link")
                        }
                    }
                }
            }

            Text(text = "Token (changes every start):", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.token,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(state.token)) },
                    ) {
                        Text("Copy")
                    }
                }
            }
            Text(
                text = "The shell runs as this app, not as root. It can run commands and touch shared storage, but nothing system-level.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(text = "Keep it running", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = onBatteryExemption,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Allow background running")
        }
        Text(
            text = "Opens the system screen to exempt WebShell from battery optimization, so Android does not stop the server in the background.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(text = "Files", style = MaterialTheme.typography.titleMedium)
        if (state.hasAllFilesAccess) {
            Text(text = "All-files access is on: the shell can reach /sdcard.", style = MaterialTheme.typography.bodyMedium)
        } else {
            OutlinedButton(
                onClick = onAllFilesAccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow all files access (optional)")
            }
            Text(
                text = "Optional. Without it the shell still works, but /sdcard needs a per-folder grant.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.log.isNotEmpty()) {
            Text(text = "Log", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    for (line in state.log.takeLast(20)) {
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
