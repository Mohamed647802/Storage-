package com.rased.space

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class AppItem(
    val label: String,
    val pkg: String,
    val app: Long,
    val data: Long,   // البيانات بدون الكاش
    val cache: Long,
    val system: Boolean,
    val key: String   // نص البحث بعد التطبيع
) {
    val total: Long get() = app + data + cache
}

class MainActivity : Activity() {

    private val REQ_UNINSTALL = 77

    private var all: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private val selected = HashSet<String>()
    private var sortKey = 0          // 0 الإجمالي, 1 التطبيق, 2 البيانات, 3 الكاش
    private var desc = true
    private var query = ""
    private var loading = false
    private var lastLoad = 0L
    private var uninstalling = false
    private val uninstallQueue = ArrayDeque<String>()

    private val loader = Executors.newSingleThreadExecutor()
    private val statsPool = Executors.newFixedThreadPool(4)
    private val iconPool = Executors.newFixedThreadPool(2)
    private val icons = LruCache<String, Drawable>(150)

    private lateinit var adapter: Adapter
    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var permPanel: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var chips: List<TextView>
    private lateinit var chipDir: TextView
    private lateinit var chipAll: TextView
    private lateinit var btnClean: Button
    private lateinit var bottomBar: View
    private lateinit var search: EditText
    private lateinit var btnClear: TextView
    private lateinit var btnTop: TextView
    private lateinit var btnBottom: TextView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_main)

        summary = findViewById(R.id.summary)
        permPanel = findViewById(R.id.permPanel)
        swipe = findViewById(R.id.swipe)
        list = findViewById(R.id.list)
        chipDir = findViewById(R.id.chipDir)
        chipAll = findViewById(R.id.chipAll)
        btnClean = findViewById(R.id.btnClean)
        bottomBar = chipAll.parent as View
        search = findViewById(R.id.search)
        btnClear = findViewById(R.id.btnClear)
        btnTop = findViewById(R.id.btnTop)
        btnBottom = findViewById(R.id.btnBottom)
        empty = findViewById(R.id.empty)
        chips = listOf(
            findViewById(R.id.chipTotal), findViewById(R.id.chipApp),
            findViewById(R.id.chipData), findViewById(R.id.chipCache)
        )

        swipe.setColorSchemeColors(0xFF7C5CFF.toInt())
        swipe.setProgressBackgroundColorSchemeColor(0xFF171C33.toInt())
        swipe.setOnRefreshListener { load(true) }

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        chips.forEachIndexed { i, c ->
            c.setOnClickListener {
                if (sortKey != i) selected.clear()
                sortKey = i
                render()
            }
        }
        chipDir.setOnClickListener { desc = !desc; render() }

        // تحديد الكل = نتائج البحث الظاهرة فقط
        chipAll.setOnClickListener {
            val pool = shown.filter { selectable(it) }.map { it.pkg }
            if (pool.isNotEmpty() && pool.all { it in selected }) selected.removeAll(pool.toSet())
            else selected.addAll(pool)
            adapter.notifyDataSetChanged()
            updateBar()
        }
        btnClean.setOnClickListener { startAction() }

        // البحث
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = norm(s?.toString() ?: "")
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                render()
                list.scrollToPosition(0)
            }
        })
        search.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        btnClear.setOnClickListener { search.setText("") }

        // أزرار أول/آخر القائمة
        btnTop.setOnClickListener { list.scrollToPosition(0) }
        btnBottom.setOnClickListener {
            val n = adapter.itemCount
            if (n > 0) list.scrollToPosition(n - 1)
        }

        adapter = Adapter()
        adapter.setHasStableIds(true)
        list.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
            setItemViewCacheSize(12)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
                }
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateJump() }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasUsageAccess()) {
            permPanel.visibility = View.VISIBLE
            return
        }
        permPanel.visibility = View.GONE
        if (uninstalling) return
        val force = CacheCleanerService.finished
        if (force) CacheCleanerService.finished = false
        if (force || all.isEmpty()) load(true)
        else if (SystemClock.elapsedRealtime() - lastLoad > 30_000) load(false)
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        else
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // تطبيع النص للبحث: حروف صغيرة، بدون تشكيل، وتوحيد الهمزات والياء والتاء المربوطة
    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ـ' -> {}
                in '\u064B'..'\u065F', '\u0670' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun updateJump() {
        btnTop.visibility = if (list.canScrollVertically(-1)) View.VISIBLE else View.GONE
        btnBottom.visibility = if (list.canScrollVertically(1)) View.VISIBLE else View.GONE
    }

    private fun load(spinner: Boolean) {
        if (loading) {
            swipe.isRefreshing = false
            return
        }
        loading = true
        if (spinner) swipe.isRefreshing = true
        loader.execute {
            val pm = packageManager
            val ssm = getSystemService(STORAGE_STATS_SERVICE) as StorageStatsManager
            val user = Process.myUserHandle()
            val tasks = pm.getInstalledApplications(0).map { ai ->
                Callable<AppItem?> {
                    try {
                        val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user)
                        val cache = st.cacheBytes
                        val data = (st.dataBytes - cache).coerceAtLeast(0)
                        val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val label = ai.loadLabel(pm).toString()
                        AppItem(
                            label, ai.packageName, st.appBytes, data, cache, sys,
                            norm(label) + " " + ai.packageName.lowercase()
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            val res = statsPool.invokeAll(tasks).mapNotNull {
                try { it.get() } catch (e: Exception) { null }
            }
            runOnUiThread {
                all = res
                lastLoad = SystemClock.elapsedRealtime()
                loading = false
                swipe.isRefreshing = false
                selected.retainAll(res.map { it.pkg }.toSet())
                render()
            }
        }
    }

    private fun value(a: AppItem): Long = when (sortKey) {
        0 -> a.total
        1 -> a.app
        2 -> a.data
        else -> a.cache
    }

    // هل يمكن تحديد هذا التطبيق في التبويب الحالي؟
    private fun selectable(a: AppItem): Boolean = when (sortKey) {
        1 -> !a.system && a.pkg != packageName   // الإزالة: تطبيقات المستخدم فقط
        2 -> a.pkg != packageName                // مسح البيانات: ما عدا هذا التطبيق
        3 -> true                                // الكاش: الكل
        else -> false
    }

    private fun render() {
        val sorted = if (desc) all.sortedByDescending { value(it) } else all.sortedBy { value(it) }
        shown = if (query.isEmpty()) sorted else {
            val tokens = query.split(' ').filter { it.isNotEmpty() }
            sorted.filter { a -> tokens.all { a.key.contains(it) } }
        }
        adapter.submit(shown)
        empty.visibility = if (shown.isEmpty() && all.isNotEmpty()) View.VISIBLE else View.GONE
        chips.forEachIndexed { i, c ->
            c.isSelected = i == sortKey
            c.setTextColor(if (i == sortKey) 0xFFFFFFFF.toInt() else 0xFFB4BAD6.toInt())
        }
        chipDir.text = if (desc) "تنازلي ↓" else "تصاعدي ↑"
        var s = "الإجمالي: ${fmt(all.sumOf { it.total })}  •  ${all.size} تطبيق  •  كاش: ${fmt(all.sumOf { it.cache })}"
        if (query.isNotEmpty()) s += "  •  نتائج: ${shown.size}"
        summary.text = s
        updateBar()
        list.post { updateJump() }
    }

    private fun updateBar() {
        if (sortKey == 0) {
            bottomBar.visibility = View.GONE
            return
        }
        bottomBar.visibility = View.VISIBLE
        val pool = shown.filter { selectable(it) }
        // الإجراء ينفَّذ على كل المحدد (حتى لو أخفاه البحث)، والعدد يظهر على الزر
        val sel = all.filter { it.pkg in selected && selectable(it) }
        val label: String
        val size: Long
        val color: Int
        when (sortKey) {
            1 -> { label = "إزالة التطبيق"; size = sel.sumOf { it.app }; color = 0xFFEF4444.toInt() }
            2 -> { label = "مسح البيانات"; size = sel.sumOf { it.data }; color = 0xFFEF4444.toInt() }
            else -> { label = "مسح الكاش"; size = sel.sumOf { it.cache }; color = 0xFFF59E0B.toInt() }
        }
        btnClean.text = if (sel.isEmpty()) label else "$label (${sel.size}) • ${fmt(size)}"
        btnClean.backgroundTintList = ColorStateList.valueOf(color)
        btnClean.setTextColor(if (sortKey == 3) 0xFF1A1442.toInt() else 0xFFFFFFFF.toInt())
        btnClean.alpha = if (sel.isEmpty()) 0.5f else 1f
        val allSel = pool.isNotEmpty() && pool.all { it.pkg in selected }
        chipAll.isSelected = allSel
        chipAll.setTextColor(if (allSel) 0xFFFFFFFF.toInt() else 0xFFB4BAD6.toInt())
    }

    private fun startAction() {
        val sel = all.filter { it.pkg in selected && selectable(it) }
        if (sel.isEmpty()) {
            Toast.makeText(this, "اختر تطبيقًا واحدًا على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        when (sortKey) {
            3 -> {
                val t = sel.filter { it.cache > 0 }.sortedByDescending { it.cache }
                if (t.isEmpty()) {
                    Toast.makeText(this, "التطبيقات المختارة ليس بها كاش", Toast.LENGTH_SHORT).show()
                    return
                }
                if (!ensureService()) return
                dialog(
                    "مسح الكاش",
                    "سيتم مسح كاش ${t.size} تطبيق (${fmt(t.sumOf { it.cache })}) تلقائيًا بفتح إعدادات كل تطبيق.\n\n" +
                        "لا تلمس الشاشة أثناء العملية، وسيعود التطبيق تلقائيًا عند الانتهاء.",
                    "ابدأ"
                ) {
                    selected.clear()
                    CacheCleanerService.start(t.map { it.pkg }, CacheCleanerService.CACHE)
                }
            }
            2 -> {
                val t = sel.filter { it.data > 0 }.sortedByDescending { it.data }
                if (t.isEmpty()) {
                    Toast.makeText(this, "التطبيقات المختارة ليس بها بيانات", Toast.LENGTH_SHORT).show()
                    return
                }
                if (!ensureService()) return
                dialog(
                    "تحذير: مسح البيانات",
                    "سيتم حذف بيانات ${t.size} تطبيق (${fmt(t.sumOf { it.data })}) نهائيًا.\n\n" +
                        "يشمل ذلك تسجيل الدخول والإعدادات والملفات والتقدم المحفوظ، ولا يمكن التراجع.\n\n" +
                        "لا تلمس الشاشة أثناء العملية.",
                    "نعم، امسح البيانات"
                ) {
                    selected.clear()
                    CacheCleanerService.start(t.map { it.pkg }, CacheCleanerService.DATA)
                }
            }
            1 -> {
                dialog(
                    "إزالة التطبيقات",
                    "سيتم طلب إزالة ${sel.size} تطبيق (${fmt(sel.sumOf { it.app })}).\n\n" +
                        "سيظهر مربع تأكيد من النظام لكل تطبيق، ويمكنك رفض أي منها.",
                    "متابعة"
                ) {
                    uninstallQueue.clear()
                    uninstallQueue.addAll(sel.map { it.pkg })
                    selected.clear()
                    uninstalling = true
                    nextUninstall()
                }
            }
        }
    }

    private fun nextUninstall() {
        val p = uninstallQueue.removeFirstOrNull()
        if (p == null) {
            uninstalling = false
            load(true)
            return
        }
        try {
            startActivityForResult(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$p"))
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true),
                REQ_UNINSTALL
            )
        } catch (e: Exception) {
            nextUninstall()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNINSTALL) nextUninstall()
    }

    private fun dialog(title: String, msg: String, ok: String, onOk: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun ensureService(): Boolean {
        if (CacheCleanerService.instance != null) return true
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("مطلوب تفعيل خدمة")
            .setMessage(
                "أندرويد لا يسمح بمسح كاش أو بيانات تطبيقات أخرى مباشرة، لذلك يحتاج راصد المساحة إلى خدمة تسهيل الوصول " +
                    "لتضغط الأزرار المناسبة نيابةً عنك.\n\n" +
                    "فعّل «راصد المساحة» من: تسهيل الوصول ← التطبيقات المثبتة.\n\n" +
                    "إن كان الخيار رماديًا (أندرويد 13+): افتح معلومات التطبيق ← ⋮ ← السماح بالإعدادات المقيّدة، ثم أعد المحاولة."
            )
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("معلومات التطبيق") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null))
                )
            }
            .setNegativeButton("إلغاء", null)
            .show()
        return false
    }

    private fun fmt(b: Long): String = Formatter.formatFileSize(this, b)

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<AppItem> = emptyList()

        fun submit(l: List<AppItem>) { items = l; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.check)
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val pkg: TextView = v.findViewById(R.id.pkg)
            val total: TextView = v.findViewById(R.id.total)
            val tApp: TextView = v.findViewById(R.id.tApp)
            val tData: TextView = v.findViewById(R.id.tData)
            val tCache: TextView = v.findViewById(R.id.tCache)
            val open: Button = v.findViewById(R.id.btnOpen)
            val info: Button = v.findViewById(R.id.btnInfo)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val h = VH(LayoutInflater.from(p.context).inflate(R.layout.item_app, p, false))
            h.itemView.setOnClickListener {
                if (sortKey == 0) return@setOnClickListener
                val pos = h.bindingAdapterPosition
                if (pos < 0) return@setOnClickListener
                val a = items[pos]
                if (!selectable(a)) {
                    val m = if (sortKey == 1) "تطبيق نظام أو هذا التطبيق، لا يمكن إزالته" else "لا يمكن تحديد هذا التطبيق"
                    Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!selected.add(a.pkg)) selected.remove(a.pkg)
                h.check.isChecked = a.pkg in selected
                updateBar()
            }
            h.open.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos < 0) return@setOnClickListener
                val i = packageManager.getLaunchIntentForPackage(items[pos].pkg)
                if (i != null) startActivity(i)
                else Toast.makeText(this@MainActivity, "لا يمكن فتح هذا التطبيق", Toast.LENGTH_SHORT).show()
            }
            h.info.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos < 0) return@setOnClickListener
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", items[pos].pkg, null))
                )
            }
            return h
        }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].pkg.hashCode().toLong()

        override fun onBindViewHolder(h: VH, pos: Int) {
            val a = items[pos]
            h.name.text = a.label
            h.pkg.text = a.pkg
            h.total.text = fmt(a.total)
            h.tApp.text = fmt(a.app)
            h.tData.text = fmt(a.data)
            h.tCache.text = fmt(a.cache)

            h.check.visibility = if (sortKey == 0) View.GONE else View.VISIBLE
            h.check.alpha = if (selectable(a)) 1f else 0.3f
            h.check.isChecked = a.pkg in selected

            h.icon.tag = a.pkg
            val cached = icons.get(a.pkg)
            if (cached != null) {
                h.icon.setImageDrawable(cached)
            } else {
                h.icon.setImageDrawable(null)
                iconPool.execute {
                    val d = try { packageManager.getApplicationIcon(a.pkg) } catch (e: Exception) { null }
                    if (d != null) {
                        icons.put(a.pkg, d)
                        runOnUiThread { if (h.icon.tag == a.pkg) h.icon.setImageDrawable(d) }
                    }
                }
            }
        }
    }
}
