package sk.martinvanco.monad.core.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.auth.presentation.register.RegisterScreen
import sk.martinvanco.monad.auth.presentation.splash.SplashScreen
import sk.martinvanco.monad.onboarding.presentation.OnboardingScreen

/**
 * Custom screen transition that provides different animations based on the screens involved
 * and the navigation direction (push/pop/replace).
 */
@Composable
fun CustomScreenTransition(
    navigator: Navigator,
    transition: ScreenTransitionSpec = defaultScreenTransitionSpec()
) {
    AnimatedContent(
        targetState = navigator.lastItem,
        transitionSpec = {
            val (initialScreen, targetScreen) = initialState to targetState
            val lastEvent = navigator.lastEvent

            transition.getTransition(initialScreen, targetScreen, lastEvent)
        },
        label = "ScreenTransition"
    ) { screen ->
        navigator.saveableState("transition", screen) {
            screen.Content()
        }
    }
}

/**
 * Specification for screen transitions.
 */
interface ScreenTransitionSpec {
    fun getTransition(
        initialScreen: Screen,
        targetScreen: Screen,
        event: StackEvent
    ): ContentTransform
}

/**
 * Default transition spec that defines custom animations per screen pair.
 */
fun defaultScreenTransitionSpec(): ScreenTransitionSpec = object : ScreenTransitionSpec {
    override fun getTransition(
        initialScreen: Screen,
        targetScreen: Screen,
        event: StackEvent
    ): ContentTransform {
        // Splash -> Login: Fade transition
        if (initialScreen is SplashScreen && targetScreen is LoginScreen) {
            return fadeTransition()
        }

        // Splash -> Onboarding: Fade transition
        if (initialScreen is SplashScreen && targetScreen is OnboardingScreen) {
            return fadeTransition()
        }

        // Onboarding -> Login: Fade transition
        if (initialScreen is OnboardingScreen && targetScreen is LoginScreen) {
            return fadeTransition()
        }

        // Login <-> Register: Horizontal slide
        if (initialScreen is LoginScreen && targetScreen is RegisterScreen) {
            // Going from Login to Register (push): slide left (Register comes from right)
            return slideLeftTransition()
        }

        if (initialScreen is RegisterScreen && targetScreen is LoginScreen) {
            // Going back from Register to Login (pop): slide right (Login comes from left)
            return slideRightTransition()
        }

        // Default transition based on navigation event
        return when (event) {
            StackEvent.Push -> slideLeftTransition()
            StackEvent.Pop -> slideRightTransition()
            StackEvent.Replace -> fadeTransition()
            else -> slideLeftTransition()
        }
    }
}

/**
 * Fade in/out transition.
 */
private fun fadeTransition(
    durationMillis: Int = 400
): ContentTransform {
    return fadeIn(
        animationSpec = tween(durationMillis)
    ) togetherWith fadeOut(
        animationSpec = tween(durationMillis)
    )
}

/**
 * Slide left transition (new screen comes from right).
 */
private fun slideLeftTransition(
    durationMillis: Int = 400
): ContentTransform {
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis)
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth },
        animationSpec = tween(durationMillis)
    )
}

/**
 * Slide right transition (new screen comes from left).
 */
private fun slideRightTransition(
    durationMillis: Int = 400
): ContentTransform {
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth },
        animationSpec = tween(durationMillis)
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis)
    )
}
