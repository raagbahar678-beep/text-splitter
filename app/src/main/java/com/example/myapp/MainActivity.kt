package com.example.myapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

// ----------------------------------------------------------------------------
// Design tokens (same palette as the Python app)
// ----------------------------------------------------------------------------
val C_BG: Int = Color.parseColor("#1b1c22")
val C_PANEL: Int = Color.parseColor("#232430")
val C_PANEL_ALT: Int = Color.parseColor("#2b2c3a")
val C_BORDER: Int = Color.parseColor("#383a4a")
val C_TEXT: Int = Color.parseColor("#f2f1ec")
val C_MUTED: Int = Color.parseColor("#9a9aab")
val C_ACCENT: Int = Color.parseColor("#f0a868")
val C_ACCENT_HOVER: Int = Color.parseColor("#f5bb85")
val C_SUCCESS: Int = Color.parseColor("#7fbf8f")
val C_DANGER: Int = Color.parseColor("#e0797d")

// ----------------------------------------------------------------------------
// Streaming helpers
// ----------------------------------------------------------------------------
class CountingInputStream(inp: InputStream) : FilterInputStream(inp) {
    @Volatile
    var count: Long = 0L

    override fun read(): Int {
        val r = super.read()
        if (r >= 0) count += 1L
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len)
        if (r > 0) count += r.toLong()
        return r
    }
}

class Progress {
    @Volatile
    var phase: Int = 0
    @Volatile
    var total: Long = -1L
    @Volatile
    var counter: CountingInputStream? = null
    @Volatile
    var files: Int = 0
}

/**
 * Streaming version of the Python splitter. It reads the text in small blocks
 * and produces exactly the same chunks as the Python build_chunks():
 *  - paragraphs are separated by "\n\s*\n" (separator stays with the paragraph)
 *  - paragraphs longer than the limit are split at the last sentence end,
 *    otherwise at the last space/newline, otherwise hard at the limit
 *  - units are packed greedily without ever exceeding the limit
 * Only about (limit + 64K) characters are ever held in memory, so file size
 * (KB, MB or GB) does not matter.
 */
class SplitEngine(
    private val reader: Reader,
    private val maxChars: Int,
    private val onChunk: (String) -> Unit
) {
    private val buf = StringBuilder()
    private var pos = 0
    private var scanResume = 0
    private var eof = false
    private var pendingCR = false
    private val cbuf = CharArray(65536)
    private val cur = StringBuilder()
    private val sentenceRe = Regex("""[.!?]["')\]]*\s""")

    private fun packerAdd(unit: String) {
        if (cur.isNotEmpty() && cur.length + unit.length > maxChars) {
            onChunk(cur.toString())
            cur.setLength(0)
            cur.append(unit)
        } else {
            cur.append(unit)
        }
    }

    private fun splitAt(window: String): Int {
        val m = sentenceRe.findAll(window).lastOrNull()
        val sentenceEnd = if (m != null) m.range.last + 1 else 0
        if (sentenceEnd > 0) return sentenceEnd
        val lastSpace = window.lastIndexOf(' ')
        val lastNewline = window.lastIndexOf('\n')
        val sp = if (lastSpace > lastNewline) lastSpace else lastNewline
        return if (sp <= 0) maxChars else sp + 1
    }

    private fun handleParagraph(p: String) {
        if (p.length > maxChars) {
            var off = 0
            while (p.length - off > maxChars) {
                val w = p.substring(off, off + maxChars)
                val s = splitAt(w)
                packerAdd(p.substring(off, off + s))
                off += s
            }
            if (off < p.length) packerAdd(p.substring(off))
        } else {
            packerAdd(p)
        }
    }

    // Reads the next block, converting \r\n and \r to \n (like Python text mode).
    private fun refill() {
        if (pos > 0) {
            buf.delete(0, pos)
            scanResume = if (scanResume - pos > 0) scanResume - pos else 0
            pos = 0
        }
        while (!eof) {
            val before = buf.length
            val n = reader.read(cbuf)
            if (n < 0) {
                if (pendingCR) {
                    pendingCR = false
                    buf.append('\n')
                }
                eof = true
                break
            }
            for (j in 0 until n) {
                val ch = cbuf[j]
                if (pendingCR) {
                    pendingCR = false
                    if (ch == '\n') {
                        buf.append('\n')
                        continue
                    } else {
                        buf.append('\n')
                    }
                }
                if (ch == '\r') {
                    pendingCR = true
                } else {
                    buf.append(ch)
                }
            }
            if (buf.length > before) break
        }
    }

    fun process() {
        while (true) {
            var sepEnd = -1
            var i = if (scanResume > pos) scanResume else pos
            val len = buf.length
            while (i < len) {
                if (buf[i] == '\n') {
                    var k = i + 1
                    var lastNl = -1
                    while (k < len && buf[k].isWhitespace()) {
                        if (buf[k] == '\n') lastNl = k
                        k++
                    }
                    if (k == len && !eof) break
                    if (lastNl != -1) {
                        sepEnd = lastNl + 1
                        break
                    }
                    i = k
                } else {
                    i++
                }
            }
            if (sepEnd >= 0) {
                handleParagraph(buf.substring(pos, sepEnd))
                pos = sepEnd
                if (scanResume < pos) scanResume = pos
                continue
            }
            scanResume = i
            val avail = buf.length - pos
            if (eof) {
                if (avail > 0) handleParagraph(buf.substring(pos))
                pos = buf.length
                break
            }
            if (avail > maxChars) {
                // Paragraph is certainly longer than the limit: emit one piece now.
                val w = buf.substring(pos, pos + maxChars)
                val s = splitAt(w)
                packerAdd(buf.substring(pos, pos + s))
                pos += s
                continue
            }
            refill()
        }
        if (cur.isNotEmpty()) {
            onChunk(cur.toString())
            cur.setLength(0)
        }
    }
}

/** Writes 1.txt, 2.txt, ... into the chosen folder (same names as the Python app). */
class FolderWriter(
    private val cr: ContentResolver,
    private val tree: Uri,
    private val header: String,
    private val footer: String
) {
    private val treeId: String = DocumentsContract.getTreeDocumentId(tree)
    private val parent: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
    private val existing = HashMap<String, String>()
    var count: Int = 0

    init {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val cursor = cr.query(
            childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )
        if (cursor != null) {
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val nm = c.getString(1)
                    if (id != null && nm != null) existing[nm] = id
                }
            }
        }
    }

    fun write(chunk: String) {
        count += 1
        val name = "$count.txt"
        val sb = StringBuilder()
        if (header.isNotEmpty()) {
            sb.append(header.trimEnd('\n')).append("\n\n")
        }
        sb.append(chunk)
        if (footer.isNotEmpty()) {
            if (!chunk.endsWith("\n")) sb.append("\n")
            sb.append("\n").append(footer.trimEnd('\n')).append("\n")
        }
        val existingId = existing[name]
        val target: Uri = if (existingId != null) {
            DocumentsContract.buildDocumentUriUsingTree(tree, existingId)
        } else {
            DocumentsContract.createDocument(cr, parent, "text/plain", name)
                ?: throw IOException("Could not create $name in the chosen folder.")
        }
        val os = cr.openOutputStream(target, "wt")
            ?: throw IOException("Could not write $name in the chosen folder.")
        os.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
    }
}

// ----------------------------------------------------------------------------
// Main activity
// ----------------------------------------------------------------------------
class MainActivity : Activity() {

    private val REQ_FILE = 101
    private val REQ_TREE = 102
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private val prog = Progress()
    private var sourceUri: Uri? = null
    private var treeUri: Uri? = null
    private var running = false

    private lateinit var fileDisplay: TextView
    private lateinit var folderDisplay: TextView
    private lateinit var maxEdit: EditText
    private lateinit var headerCheck: CheckBox
    private lateinit var footerCheck: CheckBox
    private lateinit var headerEdit: EditText
    private lateinit var footerEdit: EditText
    private lateinit var splitBtn: Button
    private lateinit var statusLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var logBox: TextView

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            updateProgress()
            ui.postDelayed(this, 150)
        }
    }

    // -- small UI helpers ---------------------------------------------------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, stroke: Int?, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) d.setStroke(dp(1), stroke)
        return d
    }

    private fun params(w: Int, h: Int, top: Int = 0, bottom: Int = 0, left: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(dp(left), dp(top), 0, dp(bottom))
        return p
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(C_PANEL, C_BORDER, 12)
        val p = dp(16)
        c.setPadding(p, p, p, p)
        return c
    }

    private fun sectionLabel(text: String): TextView {
        val t = TextView(this)
        t.text = text.uppercase(Locale.getDefault())
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(C_MUTED)
        t.letterSpacing = 0.08f
        return t
    }

    private fun smallText(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        t.setTextColor(C_MUTED)
        return t
    }

    private fun pathBox(initial: String): TextView {
        val t = TextView(this)
        t.text = initial
        t.setTextColor(C_MUTED)
        t.typeface = Typeface.MONOSPACE
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        t.background = rounded(C_PANEL_ALT, null, 8)
        t.setPadding(dp(10), dp(10), dp(10), dp(10))
        t.maxLines = 2
        t.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        return t
    }

    private fun pill(text: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(if (primary) C_BG else C_TEXT)
        val normal = if (primary) C_ACCENT else C_PANEL_ALT
        val pressed = if (primary) C_ACCENT_HOVER else C_BORDER
        val sl = StateListDrawable()
        sl.addState(intArrayOf(-android.R.attr.state_enabled), rounded(C_BORDER, null, 22))
        sl.addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, null, 22))
        sl.addState(intArrayOf(), rounded(normal, null, 22))
        b.background = sl
        b.stateListAnimator = null
        b.setPadding(dp(18), dp(9), dp(18), dp(9))
        b.isFocusable = false
        b.isFocusableInTouchMode = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun editBox(multi: Boolean): EditText {
        val e = EditText(this)
        e.setTextColor(C_TEXT)
        e.setHintTextColor(C_MUTED)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        e.background = rounded(C_PANEL_ALT, C_BORDER, 8)
        e.setPadding(dp(10), dp(8), dp(10), dp(8))
        if (multi) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            e.minLines = 3
            e.maxLines = 6
            e.gravity = Gravity.TOP or Gravity.START
        }
        return e
    }

    private fun check(text: String, onChange: (Boolean) -> Unit): CheckBox {
        val c = CheckBox(this)
        c.text = text
        c.setTextColor(C_TEXT)
        c.buttonTintList = ColorStateList.valueOf(C_ACCENT)
        c.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        return c
    }

    private fun applyToggle(box: EditText, on: Boolean) {
        box.isEnabled = on
        box.background = rounded(if (on) C_PANEL_ALT else C_BORDER, C_BORDER, 8)
        box.setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    // -- layout ----------------------------------------------------------------
    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(C_BG)
        scroll.isFillViewport = true

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        outer.setPadding(pad, pad, pad, pad)
        scroll.addView(outer, ViewGroup.LayoutParams(MATCH, WRAP))

        // Hero
        val title = TextView(this)
        title.text = "Text Splitter"
        title.setTextColor(C_TEXT)
        title.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        outer.addView(title, params(MATCH, WRAP))

        val sub = TextView(this)
        sub.text = "Split any .txt file into clean, complete pieces \u2014 no cut-off words, no cut-off paragraphs."
        sub.setTextColor(C_MUTED)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        outer.addView(sub, params(MATCH, WRAP, top = 4, bottom = 18))

        // Card 1: source file
        val card1 = card()
        card1.addView(sectionLabel("Step 1 \u00b7 Source file"), params(WRAP, WRAP))
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL
        fileDisplay = pathBox("No file chosen yet")
        row1.addView(fileDisplay, params(0, WRAP, weight = 1f))
        row1.addView(pill("Browse\u2026", false) { chooseFile() }, params(WRAP, WRAP, left = 10))
        card1.addView(row1, params(MATCH, WRAP, top = 8))
        outer.addView(card1, params(MATCH, WRAP, bottom = 14))

        // Card 2: character limit
        val card2 = card()
        card2.addView(sectionLabel("Step 2 \u00b7 Max characters per file"), params(WRAP, WRAP))
        card2.addView(
            smallText("Each split file will hold as much complete text as fits under this limit."),
            params(MATCH, WRAP, top = 2, bottom = 8)
        )
        maxEdit = editBox(false)
        maxEdit.inputType = InputType.TYPE_CLASS_NUMBER
        maxEdit.setText("4300")
        maxEdit.setSelectAllOnFocus(true)
        card2.addView(maxEdit, params(dp(160), WRAP))
        outer.addView(card2, params(MATCH, WRAP, bottom = 14))

        // Card 3: header & footer
        val card3 = card()
        card3.addView(sectionLabel("Step 3 \u00b7 Optional header & footer"), params(WRAP, WRAP))
        card3.addView(
            smallText("Text you add here gets written into every single split file."),
            params(MATCH, WRAP, top = 2, bottom = 10)
        )
        headerCheck = check("Add header (top of every file)") { on -> applyToggle(headerEdit, on) }
        card3.addView(headerCheck, params(WRAP, WRAP))
        headerEdit = editBox(true)
        applyToggle(headerEdit, false)
        card3.addView(headerEdit, params(MATCH, WRAP, top = 6, bottom = 12))
        footerCheck = check("Add footer (bottom of every file)") { on -> applyToggle(footerEdit, on) }
        card3.addView(footerCheck, params(WRAP, WRAP))
        footerEdit = editBox(true)
        applyToggle(footerEdit, false)
        card3.addView(footerEdit, params(MATCH, WRAP, top = 6))
        outer.addView(card3, params(MATCH, WRAP, bottom = 14))

        // Card 4: save location
        val card4 = card()
        card4.addView(sectionLabel("Step 4 \u00b7 Save location"), params(WRAP, WRAP))
        val row4 = LinearLayout(this)
        row4.orientation = LinearLayout.HORIZONTAL
        row4.gravity = Gravity.CENTER_VERTICAL
        folderDisplay = pathBox("No folder chosen yet")
        row4.addView(folderDisplay, params(0, WRAP, weight = 1f))
        row4.addView(pill("Choose folder\u2026", false) { chooseFolder() }, params(WRAP, WRAP, left = 10))
        card4.addView(row4, params(MATCH, WRAP, top = 8))
        outer.addView(card4, params(MATCH, WRAP, bottom = 14))

        // Action row
        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.gravity = Gravity.CENTER_VERTICAL
        splitBtn = pill("Split File", true) { runSplit() }
        actionRow.addView(splitBtn, params(WRAP, WRAP))
        statusLabel = TextView(this)
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        statusLabel.setTextColor(C_MUTED)
        actionRow.addView(statusLabel, params(0, WRAP, left = 14, weight = 1f))
        outer.addView(actionRow, params(MATCH, WRAP, top = 6, bottom = 8))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 1000
        progressBar.progressTintList = ColorStateList.valueOf(C_ACCENT)
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(C_BORDER)
        progressBar.indeterminateTintList = ColorStateList.valueOf(C_ACCENT)
        progressBar.visibility = View.GONE
        outer.addView(progressBar, params(MATCH, dp(8), bottom = 10))

        // Result log
        val logCard = card()
        logCard.addView(sectionLabel("Result"), params(WRAP, WRAP))
        logBox = TextView(this)
        logBox.setTextColor(C_TEXT)
        logBox.typeface = Typeface.MONOSPACE
        logBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        logBox.background = rounded(C_PANEL_ALT, C_BORDER, 8)
        logBox.setPadding(dp(10), dp(10), dp(10), dp(10))
        logBox.minHeight = dp(140)
        logBox.setTextIsSelectable(true)
        logCard.addView(logBox, params(MATCH, WRAP, top = 8))
        outer.addView(logCard, params(MATCH, WRAP))

        return scroll
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        setContentView(buildUi())
        restorePrefs()
    }

    // -- state ---------------------------------------------------------------------
    private fun restorePrefs() {
        val p = getSharedPreferences("splitter", Context.MODE_PRIVATE)
        maxEdit.setText(p.getString("max", "4300"))
        headerEdit.setText(p.getString("hTxt", ""))
        footerEdit.setText(p.getString("fTxt", ""))
        headerCheck.isChecked = p.getBoolean("hOn", false)
        footerCheck.isChecked = p.getBoolean("fOn", false)
        applyToggle(headerEdit, headerCheck.isChecked)
        applyToggle(footerEdit, footerCheck.isChecked)
        val t = p.getString("tree", null)
        if (t != null) {
            val u = Uri.parse(t)
            var ok = false
            for (perm in contentResolver.persistedUriPermissions) {
                if (perm.uri == u && perm.isWritePermission) ok = true
            }
            if (ok) {
                treeUri = u
                updateFolderText()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val e = getSharedPreferences("splitter", Context.MODE_PRIVATE).edit()
        e.putString("max", maxEdit.text.toString())
        e.putString("hTxt", headerEdit.text.toString())
        e.putString("fTxt", footerEdit.text.toString())
        e.putBoolean("hOn", headerCheck.isChecked)
        e.putBoolean("fOn", footerCheck.isChecked)
        val t = treeUri
        e.putString("tree", t?.toString())
        e.apply()
    }

    // -- keyboard (external keyboard support) -----------------------------------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_O -> {
                    chooseFile()
                    return true
                }
                KeyEvent.KEYCODE_D -> {
                    chooseFolder()
                    return true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    runSplit()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // -- pickers -------------------------------------------------------------------------
    private fun chooseFile() {
        if (running) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_FILE)
    }

    private fun chooseFolder() {
        if (running) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(i, REQ_TREE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        if (requestCode == REQ_FILE) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // not every provider allows persisting; reading now still works
            }
            sourceUri = uri
            val info = queryInfo(uri)
            val sizeText = if (info.second >= 0L) "  (" + formatSize(info.second) + ")" else ""
            fileDisplay.text = info.first + sizeText
            fileDisplay.setTextColor(C_TEXT)
        } else if (requestCode == REQ_TREE) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // ignore
            }
            treeUri = uri
            updateFolderText()
        }
    }

    private fun folderLabel(u: Uri): String {
        return try {
            DocumentsContract.getTreeDocumentId(u)
        } catch (e: Exception) {
            u.toString()
        }
    }

    private fun updateFolderText() {
        val t = treeUri ?: return
        folderDisplay.text = folderLabel(t)
        folderDisplay.setTextColor(C_TEXT)
    }

    private fun queryInfo(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        try {
            val c = contentResolver.query(uri, null, null, null, null)
            if (c != null) {
                c.use { cur ->
                    if (cur.moveToFirst()) {
                        val ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = cur.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni)
                        if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si)
                    }
                }
            }
        } catch (e: Exception) {
            // keep defaults
        }
        return Pair(name, size)
    }

    private fun formatSize(b: Long): String {
        val d = b.toDouble()
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", d / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", d / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", d / (1024.0 * 1024.0 * 1024.0))
        }
    }

    // -- log / status ------------------------------------------------------------------------
    private fun log(message: String, clear: Boolean = false) {
        if (clear) logBox.text = ""
        logBox.append(message + "\n")
    }

    private fun setStatus(text: String, color: Int) {
        statusLabel.text = text
        statusLabel.setTextColor(color)
    }

    private fun alert(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateProgress() {
        val counter = prog.counter
        val read: Long = if (counter != null) counter.count else 0L
        val total = prog.total
        var pct = 0
        if (total > 0L) {
            pct = ((read * 1000L) / total).toInt()
            if (pct < 0) pct = 0
            if (pct > 1000) pct = 1000
        }
        val wantIndeterminate = total <= 0L
        if (progressBar.isIndeterminate != wantIndeterminate) progressBar.isIndeterminate = wantIndeterminate
        if (!wantIndeterminate) progressBar.progress = pct
        val phaseText = if (prog.phase == 1) "Checking encoding" else "Splitting"
        val pctText = if (total > 0L) " \u00b7 " + (pct / 10).toString() + "%" else ""
        val filesText = if (prog.phase == 2) " \u00b7 " + prog.files.toString() + " file(s)" else ""
        setStatus(phaseText + pctText + filesText, C_MUTED)
    }

    // -- encoding detection (same order as Python: utf-8, cp1252, latin-1) --------------------
    private fun openIn(cr: ContentResolver, uri: Uri): InputStream {
        return cr.openInputStream(uri) ?: throw IOException("Could not open the source file.")
    }

    private fun canDecode(cr: ContentResolver, uri: Uri, cs: Charset): Boolean {
        val cin = CountingInputStream(BufferedInputStream(openIn(cr, uri), 1 shl 16))
        prog.counter = cin
        return try {
            val dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val r = InputStreamReader(cin, dec)
            r.use { rd ->
                val b = CharArray(65536)
                while (rd.read(b) >= 0) {
                    // just validating
                }
            }
            true
        } catch (e: CharacterCodingException) {
            false
        } finally {
            try {
                cin.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun detectCharset(cr: ContentResolver, uri: Uri): Charset {
        if (canDecode(cr, uri, Charsets.UTF_8)) return Charsets.UTF_8
        val w: Charset? = try {
            Charset.forName("windows-1252")
        } catch (e: Exception) {
            null
        }
        if (w != null && canDecode(cr, uri, w)) return w
        return Charsets.ISO_8859_1
    }

    // -- main action ----------------------------------------------------------------------------
    private fun runSplit() {
        if (running) return
        val src = sourceUri
        if (src == null) {
            alert("Missing file", "Please choose a source .txt file first.")
            return
        }
        val tree = treeUri
        if (tree == null) {
            alert("Missing folder", "Please choose a folder to save the split files.")
            return
        }
        val raw = maxEdit.text.toString().trim()
        val parsed = raw.toLongOrNull()
        if (parsed == null || parsed <= 0L) {
            alert("Invalid number", "Please enter a whole number greater than 0 for the character limit.")
            return
        }
        val maxChars: Int = if (parsed > 200000000L) 200000000 else parsed.toInt()
        val header: String = if (headerCheck.isChecked) headerEdit.text.toString().trim() else ""
        val footer: String = if (footerCheck.isChecked) footerEdit.text.toString().trim() else ""

        val info = queryInfo(src)
        running = true
        splitBtn.isEnabled = false
        splitBtn.text = "Splitting\u2026"
        setStatus("Working\u2026", C_MUTED)
        log("Reading: " + info.first, true)
        prog.phase = 0
        prog.files = 0
        prog.counter = null
        prog.total = info.second
        progressBar.isIndeterminate = info.second <= 0L
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        ui.postDelayed(poller, 150)

        Thread {
            try {
                val cr = contentResolver
                prog.phase = 1
                val cs = detectCharset(cr, src)
                prog.phase = 2
                runOnUiThread { log("Splitting text (no words or paragraphs will be cut off)...") }
                val writer = FolderWriter(cr, tree, header, footer)
                val lines = ArrayList<String>()
                val cin = CountingInputStream(BufferedInputStream(openIn(cr, src), 1 shl 16))
                prog.counter = cin
                val reader = InputStreamReader(cin, cs)
                try {
                    val engine = SplitEngine(reader, maxChars) { chunk ->
                        writer.write(chunk)
                        prog.files = writer.count
                        if (lines.size < 200) {
                            lines.add("  " + writer.count.toString() + ".txt  \u2014  " + chunk.length.toString() + " characters")
                        }
                    }
                    engine.process()
                } finally {
                    try {
                        reader.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                val total = writer.count
                runOnUiThread { onDone(total, lines, folderLabel(tree)) }
            } catch (e: Throwable) {
                val msg: String = e.message ?: e.toString()
                runOnUiThread { onError(msg) }
            }
        }.start()
    }

    private fun finishRun() {
        running = false
        ui.removeCallbacks(poller)
        progressBar.visibility = View.GONE
        splitBtn.isEnabled = true
        splitBtn.text = "Split File"
    }

    private fun onDone(total: Int, lines: List<String>, folder: String) {
        finishRun()
        if (total == 0) {
            log("The source file appears to be empty. Nothing to split.")
            setStatus("Nothing to split", C_DANGER)
            return
        }
        log("\nCreated " + total.toString() + " file(s) in:\n" + folder + "\n")
        val sb = StringBuilder()
        for (l in lines) sb.append(l).append("\n")
        if (total > lines.size) {
            sb.append("  \u2026 and ").append(total - lines.size).append(" more file(s)\n")
        }
        logBox.append(sb.toString())
        setStatus("Done \u2014 " + total.toString() + " file(s) created", C_SUCCESS)
    }

    private fun onError(msg: String) {
        finishRun()
        log("\nError: $msg")
        setStatus("Failed", C_DANGER)
        alert("Something went wrong", msg)
    }
}
