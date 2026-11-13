package com.rased.space

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
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
            "clear cache", "cache", "الذاكرة المؤقتة", "التخزين المؤقت", "الكاش"
        )
        private val CACHE_WORDS = listOf("cache", "مؤقت", "كاش")
        private val VERBS = listOf("clear", "delete", "free", "مسح", "حذف", "تفريغ")
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
    private var miss = 0
    private var atEnd = 0
    private var token = 0
    private var busy = false
    private var total = 0
    private var done = 0
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
        if (running && !busy) reschedule(250)
    }

    private fun begin(pkgs: List<String>, m: Int) {
        queue.clear()
        queue.addAll(pkgs)
        mode = m
        total = pkgs.size
        done = 0
        running = true
        finished = false
        next()
    }

    private fun scheduleNext(ms: Long) {
        busy = true
        val t = token
        h.postDelayed({ if (t == token && running) next() }, ms)
    }

    private fun next() {
        token++
        busy = false
        step = 0
        clicks = 0
        miss = 0
        atEnd = 0
        val pkg = queue.removeFirstOrNull()
        if (pkg == null) {
            finish()
            return
        }
        done++
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
        Toast.makeText(this, "$done / $total", Toast.LENGTH_SHORT).show()
        reschedule(900)
        // مهلة أمان: لو علق تطبيق ننتقل للتالي (والتحقق سيعيده لاحقًا)
        h.postDelayed({ if (t == token && running) next() }, 16000)
    }

    private fun finish() {
        running = false
        finished = true
        h.removeCallbacks(check)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(it)
        }
    }

    private fun reschedule(ms: Long) {
        h.removeCallbacks(check)
        h.postDelayed(check, ms)
    }

    private fun String.hasAny(l: List<String>) = l.any { contains(it) }

    private fun process() {
        if (!running || busy) return
        if (SystemClock.uptimeMillis() - launchedAt < 900) {
            reschedule(400)
            return
        }
        val root = rootInActiveWindow ?: windows.firstNotNullOfOrNull { it.root }
        if (root == null || root.packageName?.toString() == packageName) {
            reschedule(500)
            return
        }

        // مسح البيانات: بعد الضغط على الزر نبحث عن زر التأكيد
        if (mode == DATA && step >= 2) {
            val ok = findExact(root, CONFIRM_KEYS)
            if (ok != null) {
                tap(ok)
                scheduleNext(1000)
                return
            }
        }

        if (mode == CACHE) {
            val clear = find(root, CACHE_KEYS) {
                it.hasAny(CACHE_WORDS) && it.hasAny(VERBS) &&
                    !it.contains("data") && !it.contains("بيانات")
            }
            if (clear != null && clear.isVisibleToUser) {
                if (clear.isEnabled) tap(clear)
                scheduleNext(700)
                return
            }
        } else {
            val clear = find(root, DATA_KEYS) {
                it.hasAny(VERBS) &&
                    (it.contains("data") || it.contains("storage") || it.contains("بيانات") || it.contains("تخزين")) &&
                    !it.hasAny(CACHE_WORDS) && !it.contains("manage") && !it.contains("إدارة")
            }
            if (clear != null && clear.isVisibleToUser) {
                if (!clear.isEnabled) {      // لا توجد بيانات لمسحها
                    scheduleNext(200)
                    return
                }
                if (clicks < 3) {
                    clicks++
                    tap(clear)
                    step = 2
                    reschedule(800)
                    return
                }
            }
        }

        // لم نجد الزر بعد
        miss++

        if (step == 0) {
            // صفحة معلومات التطبيق: نبحث عن «التخزين» وننزل لأسفل حتى يظهر
            val st = findStorage(root)
            if (st != null && tap(st)) {
                step = 1
                miss = 0
                atEnd = 0
                reschedule(600)
                return
            }
            scrollDown(root)
            if (atEnd >= 2) {
                // وصلنا لآخر الصفحة ولم نجده: نرجع لأولها ونعيد المحاولة
                scrollToTop(root)
                atEnd = 0
            }
            reschedule(350)
            return
        }

        // داخل صفحة التخزين: ننتظر قليلًا ثم ننزل لنجد الزر
        if (miss >= 2) {
            scrollDown(root)
            if (atEnd >= 2) {
                // وصلنا للنهاية بلا زر: ربما لسنا في صفحة التخزين، نعيد البحث عنها
                atEnd = 0
                scrollToTop(root)
                val st = findStorage(root)
                if (st != null) tap(st) else step = 0
            }
        }
        reschedule(400)
    }

    private fun findStorage(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (k in STORAGE_KEYS) {
            for (n in root.findAccessibilityNodeInfosByText(k)) {
                val t = (n.text ?: n.contentDescription)?.toString()?.lowercase() ?: continue
                if (t.hasAny(BAD_STORAGE) || t.length > 45) continue
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.isVisibleToUser) return c
            }
        }
        return null
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    // تمرير لأسفل: أولًا بإجراء الوصول، وإن لم توجد قائمة نستخدم إيماءة سحب
    private fun scrollDown(root: AccessibilityNodeInfo) {
        val s = findScrollable(root)
        if (s != null) {
            if (s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) atEnd = 0 else atEnd++
        } else {
            swipe(true)
        }
    }

    private fun scrollToTop(root: AccessibilityNodeInfo) {
        val s = findScrollable(root)
        if (s != null) {
            var i = 0
            while (i < 8 && s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) i++
        } else {
            swipe(false)
        }
    }

    private fun swipe(up: Boolean) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * (if (up) 0.75f else 0.30f)
        val y2 = dm.heightPixels * (if (up) 0.30f else 0.75f)
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 250))
            .build()
        dispatchGesture(g, null, null)
    }

    // أكبر عنصر قابل للتمرير على الشاشة
    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isScrollable) {
                n.getBoundsInScreen(r)
                val a = r.width().toLong() * r.height()
                if (a > bestArea) { bestArea = a; best = n }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        return best
    }

    private fun find(
        root: AccessibilityNodeInfo,
        keys: List<String>,
        ok: (String) -> Boolean
    ): AccessibilityNodeInfo? {
        for (k in keys) {
            for (n in root.findAccessibilityNodeInfosByText(k)) {
                val t = (n.text ?: n.contentDescription)?.toString()?.lowercase() ?: continue
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
