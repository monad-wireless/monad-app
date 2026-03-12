package sk.martinvanco.monad.onboarding.presentation

import dev.icerock.moko.permissions.Permission

sealed interface OnboardingEvent {
    data class RequestPermission(val permission: Permission) : OnboardingEvent
    data object OpenAppSettings : OnboardingEvent
}
