package com.bwl.toastdemo

import android.app.Application

/**
 * Created by baiwenlong on 1/8/21.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ToastManagerCompat.init(this)
    }
}