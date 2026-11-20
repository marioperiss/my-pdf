package com.mario.mypdf

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class MyApplication : Application() {
    
    companion object {
        private const val TAG = "MyApplication"
    }
    
    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        
        // Inizializza OpenCV
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "✅ OpenCV initialized SUCCESSFULLY!")
        } else {
            Log.e(TAG, "❌ OpenCV initialization FAILED!")
        }
    }
}
