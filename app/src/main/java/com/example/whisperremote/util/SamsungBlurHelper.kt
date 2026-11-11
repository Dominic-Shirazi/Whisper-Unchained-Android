// This file provides an experimental reflection wrapper for Samsung's proprietary One UI window blur API.
// It is called by FloatingButtonService when presenting the recording style sheet overlay.
// Inputs: Target android.view.View (dimView / root overlay view).
// Outputs: Boolean indicating whether Samsung's SemBlurInfo(BLUR_MODE_WINDOW) was successfully constructed and applied.
// It accesses hidden One UI SemBlurInfo via reflection with safe try/catch fallbacks, never crashing on non-Samsung or unsupported ROMs.

package com.example.whisperremote.util

// Reason for edit: Exposed SHEET_BLUR_RADIUS constant (30) and called setRadius explicitly alongside preset per contract F-11.
// Reason for edit: Added Samsung SemBlurInfo reflection wrapper for hardware window blur experiment on One UI per contract F-10.

import android.os.Build
import android.util.Log
import android.view.View
import com.example.whisperremote.BuildConfig

object SamsungBlurHelper {
    private const val TAG = "SamsungBlurHelper"

    // Tunable blur radius for the recording style sheet overlay per contract F-11
    const val SHEET_BLUR_RADIUS = 30

    // Samsung SemBlurInfo constant values
    private const val BLUR_MODE_WINDOW = 0
    private const val BLUR_BG_REGULAR_DARK = 135

    /**
     * Attempts to apply Samsung proprietary window blur via SemBlurInfo reflection.
     * Returns true if reflection and invocation succeeded without exception, false otherwise.
     */
    fun applySamsungWindowBlur(view: View): Boolean {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            return false
        }
        return try {
            val semBlurInfoClass = Class.forName("android.view.SemBlurInfo")
            val builderClass = Class.forName("android.view.SemBlurInfo\$Builder")
            val builderCtor = builderClass.getConstructor(Int::class.javaPrimitiveType)
            val builder = builderCtor.newInstance(BLUR_MODE_WINDOW)

            // Apply color curve preset for dark frosted glass tone
            try {
                val setPresetMethod = builderClass.getMethod("setColorCurvePreset", Int::class.javaPrimitiveType)
                setPresetMethod.invoke(builder, BLUR_BG_REGULAR_DARK)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "setColorCurvePreset not supported: ${e.message}")
                }
            }

            // Apply explicit blur radius (overriding or supplementing preset) per contract F-11
            try {
                val setRadiusMethod = builderClass.getMethod("setRadius", Int::class.javaPrimitiveType)
                setRadiusMethod.invoke(builder, SHEET_BLUR_RADIUS)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "setRadius not supported: ${e.message}")
                }
            }

            val buildMethod = builderClass.getMethod("build")
            val blurInfo = buildMethod.invoke(builder)

            val semSetBlurInfoMethod = View::class.java.getMethod("semSetBlurInfo", semBlurInfoClass)
            semSetBlurInfoMethod.invoke(view, blurInfo)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Applied Samsung SemBlurInfo(BLUR_MODE_WINDOW, radius=$SHEET_BLUR_RADIUS) successfully to view")
            }
            true
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Samsung window blur reflection failed or unavailable: ${t.message}")
            }
            false
        }
    }
}
