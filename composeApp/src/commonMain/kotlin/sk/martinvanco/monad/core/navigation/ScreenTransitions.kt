package sk.martinvanco.monad.core.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransition

// Slide transition with swipe-to-go-back gesture support
@Composable
fun SlideTransitionWithGesture(navigator: Navigator) {
    ScreenTransition(
        navigator = navigator,
        transition = {
            val (initialOffset, targetOffset) = when (navigator.lastEvent) {
                StackEvent.Pop -> ({ size: Int -> -size } to { size: Int -> size })
                else -> ({ size: Int -> size } to { size: Int -> -size })
            }

            slideInHorizontally(
                initialOffsetX = initialOffset,
                animationSpec = tween(300)
            ) togetherWith slideOutHorizontally(
                targetOffsetX = targetOffset,
                animationSpec = tween(300)
            )
        }
    )
}

// Custom transition with fade and slide + gesture support
@Composable
fun FadeSlideTransition(navigator: Navigator) {
    ScreenTransition(
        navigator = navigator,
        transition = {
            val (initialOffset, targetOffset) = when (navigator.lastEvent) {
                StackEvent.Pop -> ({ size: Int -> -size } to { size: Int -> size })
                else -> ({ size: Int -> size } to { size: Int -> -size })
            }

            slideInHorizontally(
                initialOffsetX = initialOffset,
                animationSpec = tween(300)
            ) + fadeIn(animationSpec = tween(300)) togetherWith
                    slideOutHorizontally(
                        targetOffsetX = targetOffset,
                        animationSpec = tween(300)
                    ) + fadeOut(animationSpec = tween(300))
        }
    )
}

// Fade only transition
@Composable
fun FadeTransition(navigator: Navigator) {
    ScreenTransition(
        navigator = navigator,
        transition = {
            fadeIn(animationSpec = tween(400)) togetherWith
                    fadeOut(animationSpec = tween(400))
        }
    )
}
