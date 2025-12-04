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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Rocket
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import sk.martinvanco.monad.core.domain.permissions.Permission
import sk.martinvanco.monad.core.domain.permissions.PermissionStatus

class OnboardingScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<OnboardingScreenModel>()
        val state by screenModel.state.collectAsState()

        LaunchedEffect(Unit) {
            screenModel.refreshPermissions()
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                    OnboardingStepContent(
                        step = step,
                        permissionStatus = step.permission?.let { state.permissionStatuses[it] }
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
                    onSkipClick = { screenModel.onSkipClick() },
                    onSettingsClick = { screenModel.openAppSettings() }
                )

                Spacer(modifier = Modifier.weight(0.1f))
            }
        }
    }
}

@Composable
private fun OnboardingStepContent(
    step: OnboardingStep,
    permissionStatus: PermissionStatus?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val icon = getStepIcon(step)
        val iconColor = if (permissionStatus == PermissionStatus.GRANTED) {
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
            if (permissionStatus == PermissionStatus.GRANTED) {
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

        if (permissionStatus != null && permissionStatus == PermissionStatus.GRANTED) {
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
    onSkipClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val currentStep = state.currentStep
    val permission = currentStep.permission
    val permissionStatus = permission?.let { state.permissionStatuses[it] }
    val isPermissionGranted = permissionStatus == PermissionStatus.GRANTED
    val isDeniedPermanently = permissionStatus == PermissionStatus.DENIED_PERMANENTLY

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                if (isDeniedPermanently) {
                    onSettingsClick()
                } else {
                    onNextClick()
                }
            },
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

private fun getStepIcon(step: OnboardingStep): ImageVector {
    return when (step) {
        OnboardingStep.WELCOME -> Icons.Default.Rocket
        OnboardingStep.BLUETOOTH -> Icons.Default.Bluetooth
        OnboardingStep.LOCATION -> Icons.Default.LocationOn
        OnboardingStep.CAMERA -> Icons.Default.CameraAlt
        OnboardingStep.COMPLETE -> Icons.Default.CheckCircle
    }
}
