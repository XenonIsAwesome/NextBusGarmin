package app.nextbus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.nextbus.service.RelayService
import app.nextbus.ui.NextBusRoot

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the relay works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val forced by vm.darkMode.collectAsState()
            val dark = forced ?: isSystemInDarkTheme()
            val context = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { NextBusRoot(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app (re)starts the relay if it is switched on. The service ignores a second start.
        if (vm.relayEnabled.value) RelayService.start(this)
    }
}
