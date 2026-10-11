package com.infraxcoders.bmpcc.platform

import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExtendableBuilder

/** Streams one physical lens of a logical multi-lens camera (Android 9+); every use case must name the same lens. */
object PhysicalLens {
    @OptIn(ExperimentalCamera2Interop::class)
    fun <T> apply(builder: ExtendableBuilder<T>, physicalId: String?) {
        if (physicalId == null || Build.VERSION.SDK_INT < 28) return
        Camera2Interop.Extender(builder).setPhysicalCameraId(physicalId)
    }
}
