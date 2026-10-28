package io.github.zazaulola.proxmark5.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.zazaulola.proxmark5.remote.Pm3Session
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // The answer does not matter: the service and the app work without notifications.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // a device plugged in while the app was not visible: handled in onStart, a service may only be started by a visible app
    private var attached: UsbDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The launch intent comes back with every re-creation (a config change, the process killed and the app
        // reopened from Recents): only a first start is a new plug-in, or a session left by hand would reopen itself.
        if (savedInstanceState == null) handleUsbIntent(intent)
        setContent { Pm3App(vm) }
        askForNotificationsOnce()

        // re-scan the bus each time the app comes back to the foreground
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refreshDevice() }
        }
    }

    override fun onStart() {
        super.onStart()
        Pm3Session.get(this).setUiVisible(true)
        attached?.let {
            attached = null
            vm.onDeviceAttached(it)
        }
    }

    override fun onStop() {
        Pm3Session.get(this).setUiVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device == null) return
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.onDeviceAttached(device) else attached = device
    }

    // Android 13 asks before the notification of the foreground service may show. Asked once, before the
    // first connect, and without waiting for the answer.
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        // a marker in noBackupFilesDir, not a preference: a backup restore must not tell a new phone it has asked
        val marker = File(noBackupFilesDir, ASKED_NOTIFICATIONS)
        if (marker.exists()) return
        runCatching { marker.createNewFile() }
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
