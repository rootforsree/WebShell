package com.rootforsree.webshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rootforsree.webshell.ui.WebShellRoute
import com.rootforsree.webshell.ui.WebShellViewModel
import com.rootforsree.webshell.ui.theme.WebShellTheme

class MainActivity : ComponentActivity() {

    // Manual construction: one host for the life of the process, handed
    // to the ViewModel. No code generation, no annotation processor.
    private val viewModel: WebShellViewModel by lazy {
        val host = (application as WebShellApp).serverHost
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(WebShellViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return WebShellViewModel(host) as T
            }
        }
        ViewModelProvider(this, factory)[WebShellViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WebShellTheme {
                WebShellRoute(viewModel)
            }
        }
    }
}
