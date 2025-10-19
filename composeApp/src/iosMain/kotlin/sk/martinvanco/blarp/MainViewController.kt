package sk.martinvanco.blarp

import androidx.compose.ui.window.ComposeUIViewController
import sk.martinvanco.blarp.navigation.RootComponent

fun MainViewController() = ComposeUIViewController {
    val rootComponent = RootComponent()
    App(rootComponent)
}
