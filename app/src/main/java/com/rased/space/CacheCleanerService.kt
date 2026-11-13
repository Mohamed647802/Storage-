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
        const val CACHE = 0
        const val DATA = 1

        @Volatile var instance: CacheCleanerService? = null
        @Volatile var running = false
        @Volatile var finished = false

        fun start(pkgs: List<String>, mode: Int): Boolean {
            val s = instance ?: return false
            s.begin(pkgs, mode)
            return true
        }

        private val CACHE_KEYS = listOf(
            "clear cache", "مسح الذاكرة المؤقتة", "مسح ذاكرة التخزين المؤقت",
            "مسح التخزين المؤقت", "مسح الكاش", "مسح ذاكرة التخزين المؤقتة"
        )
        private val DATA_KEYS = listOf(
            "clear storage", "clear all data", "clear data",
            "مسح التخزين", "مسح كل البيانات", "مسح جميع البيانات",
            "مسح البيانات", "مسح مساحة التخزين", "مسح بيانات"
        )
        private val CONFIRM_KEYS = listOf(
            "ok", "delete", "clear", "حسنًا", "حسناً", "موافق", "موافقة", "حذف", "مسح"
        )
        private val STORAGE_KEYS = listOf("storage", "التخزين")
        private val BAD_STORAGE = listOf("clear", "مسح", "delete", "حذف")
    }

    private val h = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var mode = CACHE
    private var step = 0
    private var clicks = 0
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

    private fun begin(pkgs: List<String>, m: Int) {
        queue.clear()
        queue.addAll(pkgs)
        mode = m
        total = pkgs.size
        running = true
        finished = false
        next()
    }

    private fun next() {
        token++
        busy = false
        step = 0
        clicks = 0
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
        h.postDelayed({ if (t == token && running) next() }, 10000)
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

        // مسح البيانات: بعد الضغط على الزر نبحث عن زر التأكيد في النافذة
        if (mode == DATA && step >= 2) {
            val ok = findExact(root, CONFIRM_KEYS)
            if (ok != null) {
                busy = true
                ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val t = token
                h.postDelayed({ if (t == token && running) next() }, 1000)
                return
            }
        }

        if (mode == CACHE) {
            val clear = find(root, CACHE_KEYS) {
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
        } else {
            val clear = find(root, DATA_KEYS) {
                (it.contains("clear") || it.contains("مسح") || it.contains("delete") || it.contains("حذف")) &&
                    (it.contains("data") || it.contains("storage") || it.contains("بيانات") || it.contains("تخزين")) &&
                    !it.contains("cache") && !it.contains("مؤقت") && !it.contains("كاش") &&
                    !it.contains("manage") && !it.contains("إدارة")
            }
            if (clear != null) {
                if (!clear.isEnabled) {      // لا توجد بيانات لمسحها
                    busy = true
                    next()
                    return
                }
                if (clicks < 3) {
                    clicks++
                    clear.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    step = 2
                    reschedule(900)
                    return
                }
            }
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

    // مطابقة تامة للنص (لأزرار التأكيد فقط)
    private fun findExact(root: AccessibilityNodeInfo, keys: List<String>): AccessibilityNodeInfo? {
        for (k in keys) {
            for (n in root.findAccessibilityNodeInfosByText(k)) {
                val t = n.text?.toString()?.trim() ?: continue
                if (!t.equals(k, ignoreCase = true)) continue
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null) return c
            }
        }
        return null
    }
}
