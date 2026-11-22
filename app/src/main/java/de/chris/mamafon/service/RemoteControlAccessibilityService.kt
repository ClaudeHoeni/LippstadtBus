package de.chris.mamafon.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteControlAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun executeRemoteCommand(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val type = json.getString("type")
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()

            when (type) {
                "TAP" -> {
                    val x = (json.getDouble("x") * width).toFloat().coerceIn(1f, width - 1f)
                    val y = (json.getDouble("y") * height).toFloat().coerceIn(1f, height - 1f)
                    performTap(x, y)
                }
                "SWIPE" -> {
                    val x1 = (json.getDouble("x1") * width).toFloat().coerceIn(1f, width - 1f)
                    val y1 = (json.getDouble("y1") * height).toFloat().coerceIn(1f, height - 1f)
                    val x2 = (json.getDouble("x2") * width).toFloat().coerceIn(1f, width - 1f)
                    val y2 = (json.getDouble("y2") * height).toFloat().coerceIn(1f, height - 1f)
                    val duration = json.optLong("duration", 250L).coerceIn(50L, 1500L)
                    performSwipe(x1, y1, x2, y2, duration)
                }
                "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}