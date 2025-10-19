package sk.martinvanco.blarp

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import sk.martinvanco.blarp.navigation.RootComponent

fun MainViewController() = ComposeUIViewController {
    val lifecycle = LifecycleRegistry()
    val rootComponent = RootComponent(
        componentContext = DefaultComponentContext(lifecycle = lifecycle)
    )
    App(rootComponent)
}
