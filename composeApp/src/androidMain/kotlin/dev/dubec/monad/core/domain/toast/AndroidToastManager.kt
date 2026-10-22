package dev.dubec.monad.core.domain.toast

import android.content.Context
import android.widget.Toast

class AndroidToastManager(
    private val context: Context
) : ToastManager {
    override fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
