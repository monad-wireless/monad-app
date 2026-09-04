package sk.martinvanco.monad.lab.domain

/**
 * Android has no counterpart to Xcode's interception shims.
 *
 * StrictMode and the debuggable flag exist, but neither sits in front of every GPU draw call, and
 * reporting them here would put a warning on a build that is not at risk of the failure this check
 * exists for. An honest empty answer beats an approximate one.
 */
actual fun detectBuildDiagnostics(): BuildDiagnostics = BuildDiagnostics.NONE
