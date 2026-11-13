package com.rased.space

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class CacheCleanerService : AccessibilityService() {

    companion object {
        @Volatile var instance: CacheCleanerService? = null
        @Volatile var running = false
        @Volatile var finished = false

        fun start(pkgs: List<String>): Boolean {
            val s = instance ?: return false
            s.begin(pkgs)
            return true
        }

        private val CLEAR_KEYS = listOf(
            "clear cache", "مسح الذاكرة المؤقتة", "مسح ذاكرة التخزين المؤقت",
            "مسح التخزين المؤقت", "مسح الكاش", "مسح ذاكرة التخزين المؤقتة"
        )
        private val STORAGE_KEYS = listOf("storage", "التخزين")
        private val BAD_STORAGE = listOf("clear", "مسح", "delete", "حذف")
    }

    private val h = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var step = 0
    private var token = 0
    private var busy = false
    private var total = 0
    private var launchedAt = 0L
    private val check = Runnable { process() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        running = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (running) reschedule(300)
    }

    private fun begin(pkgs: List<String>) {
        queue.clear()
        queue.addAll(pkgs)
        total = pkgs.size
        running = true
        finished = false
        next()
    }

    private fun next() {
        token++
        busy = false
        step = 0
        val pkg = queue.removeFirstOrNull()
        if (pkg == null) {
            finish()
            return
        }
        val t = token
        launchedAt = SystemClock.uptimeMillis()
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (e: Exception) {
            next()
            return
        }
        reschedule(1300)
        // مهلة أمان: لو علق تطبيق ننتقل للتالي
        h.postDelayed({ if (t == token && running) next() }, 9000)
    }

    private fun finish() {
        running = false
        finished = true
        h.removeCallbacks(check)
        Toast.makeText(this, "تمت معالجة $total تطبيق", Toast.LENGTH_LONG).show()
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun reschedule(ms: Long) {
        h.removeCallbacks(check)
        h.postDelayed(check, ms)
    }

    private fun process() {
        if (!running || busy) return
        if (SystemClock.uptimeMillis() - launchedAt < 1200) {
            reschedule(600)
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() == packageName) {
            reschedule(800)
            return
        }

        val clear = find(root, CLEAR_KEYS) {
            (it.contains("cache") || it.contains("مؤقت") || it.contains("كاش")) &&
                !it.contains("data") && !it.contains("بيانات")
        }
        if (clear != null) {
            busy = true
            if (clear.isEnabled) clear.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val t = token
            h.postDelayed({ if (t == token && running) next() }, 700)
            return
        }

        if (step == 0) {
            val st = find(root, STORAGE_KEYS) { s -> BAD_STORAGE.none { s.contains(it) } }
            if (st != null && st.performAction(AccessibilityNodeInfo.ACTION_CLICK)) step = 1
        }
        reschedule(800)
    }

    private fun find(
        root: AccessibilityNodeInfo,
        keys: List<String>,
        ok: (String) -> Boolean
    ): AccessibilityNodeInfo? {
        for (k in keys) {
            for (n in root.findAccessibilityNodeInfosByText(k)) {
                val t = n.text?.toString()?.lowercase() ?: continue
                if (!ok(t)) continue
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null) return c
            }
        }
        return null
    }
}
