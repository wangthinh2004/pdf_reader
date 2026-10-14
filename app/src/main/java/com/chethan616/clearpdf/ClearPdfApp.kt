package com.chethan616.clearpdf

import android.app.Activity
import com.chethan616.clearpdf.ui.activity.welcome.WelcomeActivity
import com.sdk.core_admob_iaa.AdsApplication
import dagger.hilt.android.HiltAndroidApp

/** Root of the Hilt dependency graph. */
@HiltAndroidApp
class ClearPdfApp : AdsApplication() {
    override val welcomeView: Class<out Activity>
        get() = WelcomeActivity::class.java

    override val remoteConfigDefaultsResId: Int
        get() = R.xml.remote_config_defaults
}
