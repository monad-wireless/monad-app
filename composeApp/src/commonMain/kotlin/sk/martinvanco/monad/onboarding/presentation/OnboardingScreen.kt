package sk.martinvanco.monad.onboarding.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import kotlinx.coroutines.withTimeoutOrNull
import sk.martinvanco.monad.core.config.AppConfig

class OnboardingScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<OnboardingScreenModel>()
        val state by screenModel.state.collectAsState()

        val factory = rememberPermissionsControllerFactory()
        val controller = remember(factory) { factory.createPermissionsController() }
        BindEffect(controller)

        // Track resume count to re-trigger permission checks when returning from Settings
        var resumeCount by remember { mutableStateOf(0) }
        LifecycleResumeEffect(Unit) {
            resumeCount++
            onPauseOrDispose { }
        }

        // Check permission statuses on every resume
        LaunchedEffect(controller, resumeCount) {
            OnboardingStep.entries.forEach { step ->
                step.permission?.let { permission ->
                    val granted = controller.isPermissionGranted(permission)
                    screenModel.updatePermissionStatus(permission, granted)
                }
            }
        }

        // Handle events from ScreenModel
        LaunchedEffect(Unit) {
            screenModel.events.collect { event ->
                when (event) {
                    is OnboardingEvent.RequestPermission -> {
                        try {
                            val result = withTimeoutOrNull(30_000L) {
                                controller.providePermission(event.permission)
                                true
                            }
                            if (result == true) {
                                screenModel.onPermissionResult(event.permission, granted = true, deniedPermanently = false)
                            } else {
                                screenModel.onPermissionResult(event.permission, granted = false, deniedPermanently = false)
                            }
                        } catch (e: DeniedAlwaysException) {
                            screenModel.onPermissionResult(event.permission, granted = false, deniedPermanently = true)
                        } catch (e: DeniedException) {
                            screenModel.onPermissionResult(event.permission, granted = false, deniedPermanently = false)
                        } catch (e: Exception) {
                            screenModel.onPermissionResult(event.permission, granted = false, deniedPermanently = false)
                        }
                    }
                    is OnboardingEvent.OpenAppSettings -> {
                        controller.openAppSettings()
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(0.1f))

                AnimatedContent(
                    targetState = state.currentStep,
                    transitionSpec = {
                        (slideInHorizontally(
                            initialOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(300)
                        ) + fadeIn(animationSpec = tween(300))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { fullWidth -> -fullWidth },
                            animationSpec = tween(300)
                        ) + fadeOut(animationSpec = tween(300)))
                    },
                    modifier = Modifier.weight(0.6f),
                    label = "OnboardingContent"
                ) { step ->
                    val isGranted = step.permission?.let { state.isPermissionGranted(it) } ?: false
                    OnboardingStepContent(
                        step = step,
                        isPermissionGranted = isGranted
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                PageIndicator(
                    currentPage = state.currentPage,
                    totalPages = state.totalPages
                )

                Spacer(modifier = Modifier.height(32.dp))

                OnboardingButtons(
                    state = state,
                    onNextClick = { screenModel.onNextClick() },
                    onSkipClick = { screenModel.onSkipClick() }
                )

                Spacer(modifier = Modifier.weight(0.1f))
            }
        }
    }
}

@Composable
private fun OnboardingStepContent(
    step: OnboardingStep,
    isPermissionGranted: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val icon = getStepIcon(step)
        val iconColor = if (isPermissionGranted) {
            Color(0xFF4CAF50)
        } else {
            MaterialTheme.colorScheme.primary
        }

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            if (isPermissionGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Permission Granted",
                    modifier = Modifier.size(64.dp),
                    tint = iconColor
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = step.title,
                    modifier = Modifier.size(64.dp),
                    tint = iconColor
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = step.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = step.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (step == OnboardingStep.TERMS) {
            Spacer(modifier = Modifier.height(16.dp))
            TermsLinks()
        }

        if (step.permission != null && isPermissionGranted) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Permission Granted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(
    currentPage: Int,
    totalPages: Int
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalPages) { index ->
            val isSelected = index == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (isSelected) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}

@Composable
private fun OnboardingButtons(
    state: OnboardingState,
    onNextClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    val currentStep = state.currentStep
    val permission = currentStep.permission
    val isPermissionGranted = permission?.let { state.isPermissionGranted(it) } ?: false
    val isDeniedPermanently = permission?.let { state.isPermissionDeniedPermanently(it) } ?: false

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onNextClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !state.isLoading,
            colors = if (isPermissionGranted && permission != null) {
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                val buttonText = when {
                    isDeniedPermanently -> "Open Settings"
                    isPermissionGranted && permission != null -> "Continue"
                    else -> currentStep.buttonText
                }

                if (isDeniedPermanently) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (permission != null && !isPermissionGranted && !state.isLastPage) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = onSkipClick,
                enabled = !state.isLoading
            ) {
                Text(
                    text = "Skip for now",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun TermsLinks() {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)

    val annotatedText = buildAnnotatedString {
        withStyle(SpanStyle(color = textColor)) {
            append("Read our ")
        }
        pushStringAnnotation(tag = "URL", annotation = "${AppConfig.BASE_URL}/terms")
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Terms & Conditions")
        }
        pop()
        withStyle(SpanStyle(color = textColor)) {
            append(" and ")
        }
        pushStringAnnotation(tag = "URL", annotation = "${AppConfig.BASE_URL}/privacy-policy")
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append("Privacy Policy")
        }
        pop()
    }

    ClickableText(
        text = annotatedText,
        style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
        onClick = { offset ->
            annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    uriHandler.openUri(annotation.item)
                }
        }
    )
}

private fun getStepIcon(step: OnboardingStep): ImageVector {
    return when (step) {
        OnboardingStep.WELCOME -> Icons.Default.Rocket
        OnboardingStep.BLUETOOTH -> Icons.Default.Bluetooth
        OnboardingStep.LOCATION -> Icons.Default.LocationOn
        OnboardingStep.BACKGROUND_LOCATION -> Icons.Default.MyLocation
        OnboardingStep.CAMERA -> Icons.Default.CameraAlt
        OnboardingStep.TERMS -> Icons.Default.Security
        OnboardingStep.COMPLETE -> Icons.Default.CheckCircle
    }
}
