package com.margelo.nitro.nitroclouduploader

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider

class NitroCloudUploaderPackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
        // Store context for Nitro modules to access
        appContext = reactContext.applicationContext
        return null
    }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
        return ReactModuleInfoProvider { HashMap() }
    }

    companion object {
        // Static context for Nitro modules (since Nitro uses no-arg constructor)
        @JvmStatic
        var appContext: android.content.Context? = null
            internal set

        init {
            System.loadLibrary("nitroclouduploader")
        }
    }
}
