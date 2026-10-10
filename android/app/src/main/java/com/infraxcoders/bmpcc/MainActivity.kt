package com.infraxcoders.bmpcc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.ui.AppNav
import com.infraxcoders.bmpcc.ui.BMPCCTheme
import com.infraxcoders.bmpcc.ui.Brand
import com.infraxcoders.bmpcc.ui.Navigator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.infraxcoders.bmpcc.platform.CrashLog.install(this)
        RecceStore.init(this)
        Settings.init(this)
        com.infraxcoders.bmpcc.data.LutStore.init(this)
        com.infraxcoders.bmpcc.ble.CameraLink.init(this)
        setContent {
            BMPCCTheme {
                val nav = remember { Navigator() }
                Box(Modifier.fillMaxSize().background(Brand.background)) { AppNav(nav) }
            }
        }
    }
}
