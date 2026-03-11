package sk.martinvanco.monad.core.domain.toast

class IosToastManager : ToastManager {
    override fun showToast(message: String) {
        // iOS doesn't have native toast - handled in UI layer
    }
}
