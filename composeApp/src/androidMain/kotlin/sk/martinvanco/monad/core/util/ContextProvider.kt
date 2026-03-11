package sk.martinvanco.monad.core.util

import android.app.Activity
import android.app.Application
import android.content.Context
import java.lang.ref.WeakReference

object ContextProvider {
    private lateinit var application: Application
    private var activityRef: WeakReference<Activity>? = null

    fun init(application: Application) {
        this.application = application
    }

    fun getContext(): Context = application.applicationContext

    fun setActivity(activity: Activity?) {
        activityRef = activity?.let { WeakReference(it) }
    }

    fun getActivity(): Activity? = activityRef?.get()
}
