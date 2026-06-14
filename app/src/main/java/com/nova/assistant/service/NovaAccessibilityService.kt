package com.nova.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class NovaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NovaAccessibilityService? = null
        val handler = Handler(Looper.getMainLooper())
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun executeAction(action: JSONObject, onDone: () -> Unit = {}) {
        val type = action.getString("type")
        val params = action.optJSONObject("params") ?: JSONObject()
        handler.post {
            when (type) {
                "OPEN_APP"   -> openApp(params.getString("app"), onDone)
                "TAP_TEXT"   -> tapByText(params.getString("text"), onDone)
                "TYPE_TEXT"  -> typeText(params.getString("text"), onDone)
                "PRESS_KEY"  -> pressKey(params.getString("key"), onDone)
                "SCROLL"     -> scroll(params.getString("direction"), params.optInt("amount", 3), onDone)
                "SWIPE"      -> swipe(params.getString("direction"), onDone)
                "TAP_COORDS" -> tapCoords(params.getInt("x"), params.getInt("y"), onDone)
                "SEQUENCE"   -> runSequence(params.getJSONArray("steps"), onDone)
                else         -> onDone()
            }
        }
    }

    private fun openApp(appName: String, onDone: () -> Unit) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        val target = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true)
        } ?: apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
        }
        val launch = target?.let { pm.getLaunchIntentForPackage(it.packageName) }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(launch)
            handler.postDelayed({ onDone() }, 1500)
        } else onDone()
    }

    private fun tapByText(text: String, onDone: () -> Unit) {
        val root = rootInActiveWindow ?: run { onDone(); return }
        val node = root.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.isClickable }
            ?: root.findAccessibilityNodeInfosByText(text)?.firstOrNull()
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({ onDone() }, 500)
    }

    private fun typeText(text: String, onDone: () -> Unit) {
        val root = rootInActiveWindow
        val focused = findEditable(root)
        if (focused != null) {
            val args = Bundle()
            args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("nova", text))
            root?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        handler.postDelayed({ onDone() }, 400)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val f = findEditable(node.getChild(i)); if (f != null) return f
        }
        return null
    }

    private fun pressKey(key: String, onDone: () -> Unit) {
        when (key.uppercase()) {
            "BACK"    -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME"    -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "ENTER", "SEARCH" -> {
                val root = rootInActiveWindow
                findEditable(root)?.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
            }
        }
        handler.postDelayed({ onDone() }, 400)
    }

    private fun scroll(direction: String, amount: Int, onDone: () -> Unit) {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val dist = dm.heightPixels * 0.35f
        repeat(amount.coerceAtMost(5)) { i ->
            handler.postDelayed({
                val path = Path()
                if (direction.uppercase() == "DOWN") { path.moveTo(cx, cy + dist); path.lineTo(cx, cy - dist) }
                else { path.moveTo(cx, cy - dist); path.lineTo(cx, cy + dist) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 200)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            }, i * 300L)
        }
        handler.postDelayed({ onDone() }, amount * 300L + 300)
    }

    private fun swipe(direction: String, onDone: () -> Unit) {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f; val cy = dm.heightPixels / 2f; val d = dm.widthPixels * 0.4f
        val path = Path()
        when (direction.uppercase()) {
            "LEFT"  -> { path.moveTo(cx + d, cy); path.lineTo(cx - d, cy) }
            "RIGHT" -> { path.moveTo(cx - d, cy); path.lineTo(cx + d, cy) }
            "UP"    -> { path.moveTo(cx, cy + d); path.lineTo(cx, cy - d) }
            "DOWN"  -> { path.moveTo(cx, cy - d); path.lineTo(cx, cy + d) }
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
        handler.postDelayed({ onDone() }, 500)
    }

    private fun tapCoords(x: Int, y: Int, onDone: () -> Unit) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
        handler.postDelayed({ onDone() }, 300)
    }

    private fun runSequence(steps: JSONArray, onDone: () -> Unit) {
        fun run(i: Int) {
            if (i >= steps.length()) { onDone(); return }
            executeAction(steps.getJSONObject(i)) { handler.postDelayed({ run(i + 1) }, 600) }
        }
        run(0)
    }
}
