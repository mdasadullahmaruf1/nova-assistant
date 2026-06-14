package com.nova.assistant.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nova.assistant.R
import com.nova.assistant.service.NovaAccessibilityService
import com.nova.assistant.service.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAccessStatus: TextView
    private lateinit var tvMicStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnLaunch: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus       = findViewById(R.id.tvStatus)
        tvAccessStatus = findViewById(R.id.tvAccessibilityStatus)
        tvMicStatus    = findViewById(R.id.tvMicStatus)
        tvOverlayStatus= findViewById(R.id.tvOverlayStatus)
        btnLaunch      = findViewById(R.id.btnLaunch)

        findViewById<android.view.View>(R.id.cardAccessibility).setOnClickListener { openAccessibility() }
        findViewById<android.view.View>(R.id.cardMic).setOnClickListener { requestMic() }
        findViewById<android.view.View>(R.id.cardOverlay).setOnClickListener { requestOverlay() }
        btnLaunch.setOnClickListener { launchNova() }
    }

    override fun onResume() { super.onResume(); updateUI() }

    private fun updateUI() {
        val accessOk  = isAccessibilityOn()
        val micOk     = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)

        tvAccessStatus.text  = if (accessOk) "✓ ENABLED" else "ENABLE →"
        tvAccessStatus.setTextColor(if (accessOk) 0xFF34D399.toInt() else 0xFF6366F1.toInt())
        tvMicStatus.text     = if (micOk) "✓ GRANTED" else "GRANT →"
        tvMicStatus.setTextColor(if (micOk) 0xFF34D399.toInt() else 0xFF6366F1.toInt())
        tvOverlayStatus.text = if (overlayOk) "✓ GRANTED" else "GRANT →"
        tvOverlayStatus.setTextColor(if (overlayOk) 0xFF34D399.toInt() else 0xFF6366F1.toInt())

        val allOk = accessOk && micOk && overlayOk
        btnLaunch.isEnabled = allOk
        tvStatus.text = if (allOk) "● READY" else "● SETUP NEEDED"
        tvStatus.setTextColor(if (allOk) 0xFF34D399.toInt() else 0xFFF59E0B.toInt())
    }

    private fun isAccessibilityOn(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openAccessibility() {
        Toast.makeText(this, "Find NOVA Assistant and enable it", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestMic() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results); updateUI()
    }

    private fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun launchNova() {
        if (NovaAccessibilityService.instance == null) {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
            openAccessibility(); return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "NOVA launched! Tap ✦ anywhere to speak.", Toast.LENGTH_LONG).show()
        finish()
    }
}
