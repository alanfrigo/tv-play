package com.tvplay

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var ipField: EditText
    private lateinit var passwordField: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var urlView: TextView
    private lateinit var statusView: TextView
    private val settings by lazy { getSharedPreferences("capture_status", MODE_PRIVATE) }
    private val ipSettings by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private val projectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    private var pendingIp: String? = null
    private var pendingPassword: String? = null
    private var startingService = false
    private var captureService: CaptureService? = null
    private var serviceState: CaptureService.State? = null
    private var serviceMessage = ""
    private var localMessage: String? = null

    private var bound = false

    private val listener: (CaptureService.State, String) -> Unit = { state, message ->
        serviceState = state
        serviceMessage = message
        if (pendingIp == null && (!startingService || state != CaptureService.State.IDLE)) {
            startingService = false
            localMessage = null
        }
        render()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            captureService = (binder as CaptureService.LocalBinder).service
            captureService?.observe(listener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            captureService = null
            serviceState = null
            startingService = false
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ipField = findViewById(R.id.nas_ip)
        passwordField = findViewById(R.id.publish_password)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        urlView = findViewById(R.id.viewer_url)
        statusView = findViewById(R.id.status)

        ipField.setText(ipSettings.getString("nas_ip", ""))
        ipField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderUrl()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        startButton.setOnClickListener { requestStart() }
        stopButton.setOnClickListener { requestStop() }
        renderUrl()
        render()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }


    override fun onStart() {
        super.onStart()
        bindIfRunning()
    }

    override fun onStop() {
        if (bound) {
            captureService?.removeObserver(listener)
            unbindService(connection)
            bound = false
        }
        captureService = null
        serviceState = null
        super.onStop()
    }

    override fun onDestroy() {
        clearPending()
        super.onDestroy()
    }

    private fun bindIfRunning() {
        if (!bound) {
            bound = bindService(Intent(this, CaptureService::class.java), connection, 0)
        }
        render()
    }

    private fun requestStart() {
        if (pendingIp != null || startingService ||
            serviceState == CaptureService.State.STARTING || serviceState == CaptureService.State.LIVE ||
            serviceState == CaptureService.State.STOPPING
        ) return
        val ip = ipField.text.toString()
        if (!isPrivateIPv4(ip)) {
            ipField.error = getString(R.string.invalid_ip)
            ipField.requestFocus()
            return
        }
        val password = passwordField.text.toString()
        if (password.isEmpty()) {
            passwordField.error = getString(R.string.missing_password)
            passwordField.requestFocus()
            return
        }
        ipSettings.edit().putString("nas_ip", ip).apply()
        localMessage = null
        pendingIp = ip
        pendingPassword = password
        render()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestProjection()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQUEST)
        }
    }

    private fun requestProjection() {
        if (pendingIp == null || pendingPassword == null) return
        try {
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            startActivityForResult(intent, PROJECTION_REQUEST)
        } catch (_: ActivityNotFoundException) {
            projectionUnavailable()
        } catch (_: SecurityException) {
            projectionUnavailable()
        }
    }

    @Deprecated("Activity result API has no dependency on AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PROJECTION_REQUEST) return
        val ip = pendingIp
        val password = pendingPassword
        clearPending()
        if (resultCode != RESULT_OK || data == null || ip == null || password == null) {
            localMessage = getString(R.string.capture_denied)
            render()
            return
        }
        try {
            startingService = true
            val start = Intent(this, CaptureService::class.java).apply {
                action = "com.tvplay.START"
                putExtra("nas_ip", ip)
                putExtra("publish_password", password)
                putExtra("result_code", resultCode)
                putExtra("result_data", data)
            }
            startForegroundService(start)
            passwordField.text?.clear()
            bindIfRunning()
        } catch (_: ActivityNotFoundException) {
            projectionUnavailable()
        } catch (_: SecurityException) {
            projectionUnavailable()
        } catch (_: IllegalStateException) {
            projectionUnavailable()
        }
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != AUDIO_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestProjection()
        } else {
            clearPending()
            localMessage = getString(R.string.audio_required)
            render()
        }
    }

    private fun requestStop() {
        if (serviceState != CaptureService.State.STARTING &&
            serviceState != CaptureService.State.LIVE && !startingService
        ) return
        val stop = Intent(this, CaptureService::class.java).apply { action = "com.tvplay.STOP" }
        startService(stop)
        startingService = false
        render()
    }

    private fun projectionUnavailable() {
        clearPending()
        startingService = false
        localMessage = getString(R.string.capture_unavailable)
        render()
    }

    private fun clearPending() {
        pendingIp = null
        pendingPassword = null
    }

    private fun renderUrl() {
        val ip = ipField.text.toString()
        urlView.text = if (isPrivateIPv4(ip)) "http://$ip:8889/tv/" else ""
    }

    private fun render() {
        val pending = pendingIp != null || startingService
        val running = serviceState == CaptureService.State.STARTING ||
            serviceState == CaptureService.State.LIVE || serviceState == CaptureService.State.STOPPING
        ipField.isEnabled = !pending && !running
        passwordField.isEnabled = !pending && !running
        startButton.isEnabled = !pending && !running
        stopButton.isEnabled = startingService || serviceState == CaptureService.State.STARTING ||
            serviceState == CaptureService.State.LIVE
        statusView.text = when {
            pending -> getString(R.string.starting)
            serviceState == CaptureService.State.STARTING -> serviceMessage.ifEmpty { getString(R.string.starting) }
            serviceState == CaptureService.State.STOPPING -> getString(R.string.stopping)
            serviceState == CaptureService.State.LIVE -> {
                "${serviceMessage.ifEmpty { getString(R.string.live) }}\n${getString(R.string.go_home)}"
            }
            localMessage != null -> localMessage
            serviceState != null -> serviceMessage.ifEmpty { getString(R.string.idle) }
            else -> settings.getString("terminal_message", null) ?: getString(R.string.idle)
        }
    }

    companion object {
        private const val NOTIFICATION_REQUEST = 1
        private const val AUDIO_REQUEST = 2
        private const val PROJECTION_REQUEST = 3

        // Accept only canonical dotted-decimal RFC1918 literals, never resolve hostnames.
        internal fun isPrivateIPv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            val octets = IntArray(4)
            for (index in 0..3) {
                val part = parts[index]
                if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0') ||
                    part.any { it !in '0'..'9' }
                ) return false
                octets[index] = part.toIntOrNull() ?: return false
                if (octets[index] > 255) return false
            }
            return when (octets[0]) {
                10 -> (octets[1] != 0 || octets[2] != 0 || octets[3] != 0) &&
                    (octets[1] != 255 || octets[2] != 255 || octets[3] != 255)
                172 -> octets[1] in 16..31 &&
                    (octets[1] != 16 || octets[2] != 0 || octets[3] != 0) &&
                    (octets[1] != 31 || octets[2] != 255 || octets[3] != 255)
                192 -> octets[1] == 168 &&
                    (octets[2] != 0 || octets[3] != 0) &&
                    (octets[2] != 255 || octets[3] != 255)
                else -> false
            }
        }
    }
}
