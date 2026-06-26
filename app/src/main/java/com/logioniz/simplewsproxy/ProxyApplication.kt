package com.logioniz.simplewsproxy

import android.app.Application
import com.logioniz.simplewsproxy.data.SettingsStore

/** Initializes process-wide singletons before the Activity or Service start. */
class ProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
    }
}
