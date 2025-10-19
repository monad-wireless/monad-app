package sk.martinvanco.blarp.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.Serializable

class RootComponent(
    componentContext: ComponentContext,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Configuration>()

    val childStack: Value<ChildStack<Configuration, Child>> = childStack(
        source = navigation,
        serializer = Configuration.serializer(),
        initialConfiguration = Configuration.Home,
        handleBackButton = true,
        childFactory = ::createChild,
        key = "MainStack"
    )

    private fun createChild(config: Configuration, context: ComponentContext): Child {
        return when (config) {
            is Configuration.Home -> Child.HomeChild(Unit)
            // Add your feature screens here
        }
    }

    sealed class Child {
        data class HomeChild(val unit: Unit) : Child()
        // Add your feature child components here
    }

    @Serializable
    sealed class Configuration {
        @Serializable
        data object Home : Configuration()
        // Add your feature configurations here
    }
}
