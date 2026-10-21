package com.pharmacy.photobarcodemvp

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.paddle.ocr.PaddleOCR
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private data class Entry(var type: String, var value: String, var confirmed: Boolean = false)
    private val entries = mutableListOf<Entry>()
    private lateinit var listContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var barcodeImage: ImageView
    private lateinit var barcodeValue: TextView
    private lateinit var barcodePanel: LinearLayout
    private lateinit var counter: TextView
    private var currentBarcodeIndex = 0
    private var pendingCameraUri: Uri? = null
    private var paddleOCR: PaddleOCR? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingCameraUri?.let { runOcr(it) } ?: run { status.text = "找不到拍照圖片，請重新拍攝或選擇照片。" }
        else status.text = "已取消拍照"
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runOcr(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderEntries()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(244, 248, 252))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(TextView(this).apply {
            text = "手機照片條碼輸入器"; textSize = 24f
            setTextColor(Color.rgb(24, 54, 84)); typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "PP-OCRv5 離線辨識完整文字，選取候選值後人工確認並產生 Code 128 條碼。"
            textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, dp(6), 0, dp(12))
        })
        val photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        photoRow.addView(button("拍照") { launchCamera() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        photoRow.addView(button("選照片") { pickImage.launch("image/*") }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        content.addView(photoRow)
        status = TextView(this).apply {
            text = "首次辨識會載入本機 PP-OCRv5 模型；請選擇處方箋照片。"
            textSize = 14f; setTextColor(Color.rgb(70, 85, 100)); setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(status)
        content.addView(TextView(this).apply {
            text = "辨識候選資料（可編輯、調整順序或刪除）"; textSize = 17f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(24, 54, 84)); setPadding(0, dp(8), 0, dp(8))
        })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)
        content.addView(button("查看最近一次完整 OCR 文字") { showFullText() })
        content.addView(TextView(this).apply {
            text = "手動新增"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 54, 84)); setPadding(0, dp(16), 0, dp(6))
        })
        val typeSpinner = Spinner(this)
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("健保碼", "醫事機構代碼", "身分證字號", "其他文字"))
        content.addView(typeSpinner)
        val manualInput = EditText(this).apply { hint = "輸入或修正文字"; setSingleLine(true); textSize = 16f }
        content.addView(manualInput)
        content.addView(button("新增到條碼清單") {
            val value = manualInput.text.toString().trim()
            if (value.isBlank()) status.text = "請先輸入文字"
            else {
                entries.add(Entry(typeSpinner.selectedItem.toString(), value, true))
                manualInput.text.clear(); renderEntries(); status.text = "已新增，請確認順序"
            }
        })
        content.addView(button("開始條碼輸出") {
            if (entries.isEmpty()) status.text = "目前沒有資料，請先辨識或手動新增"
            else { currentBarcodeIndex = 0; renderBarcode() }
        })
        barcodePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12)); setBackgroundColor(Color.WHITE); visibility = View.GONE
        }
        counter = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }
        barcodeValue = TextView(this).apply {
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.BLACK); setPadding(0, dp(8), 0, dp(8))
        }
        barcodeImage = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Code 128 條碼" }
        barcodePanel.addView(counter)
        barcodePanel.addView(barcodeImage, LinearLayout.LayoutParams(-1, dp(180)))
        barcodePanel.addView(barcodeValue)
        barcodePanel.addView(TextView(this).apply {
            text = "請讓掃描器只讀取畫面中央的這一個條碼。掃描完成後再按下一筆。"
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY)
        })
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(button("上一筆") { if (currentBarcodeIndex > 0) { currentBarcodeIndex--; renderBarcode() } },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        nav.addView(button("下一筆") {
            if (currentBarcodeIndex < entries.lastIndex) { currentBarcodeIndex++; renderBarcode() }
            else status.text = "已到最後一筆"
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        barcodePanel.addView(nav); content.addView(barcodePanel)
        root.addView(TextView(this).apply {
            text = "注意：OCR 結果可能有誤；涉及健保或身分資料，輸出前務必人工核對。"
            textSize = 12f; setTextColor(Color.rgb(160, 65, 35)); setPadding(0, dp(8), 0, 0)
        })
        setContentView(root)
    }

    private var lastFullOcrText: String = ""
    private fun showFullText() {
        android.app.AlertDialog.Builder(this)
            .setTitle("完整 OCR 文字")
            .setMessage(if (lastFullOcrText.isBlank()) "尚無辨識結果。" else lastFullOcrText)
            .setPositiveButton("關閉", null)
            .setNeutralButton("複製文字") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR文字", lastFullOcrText))
                status.text = "已複製完整辨識文字"
            }.show()
    }

    private fun launchCamera() {
        try { val uri = createCameraUri(); pendingCameraUri = uri; takePicture.launch(uri) }
        catch (e: Exception) { status.text = "無法開啟相機，請改用「選照片」。${e.message ?: ""}" }
    }
    private fun createCameraUri(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "prescription_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("無法建立拍照檔案")
    }

    private fun runOcr(uri: Uri) {
        status.text = "正在載入 PP-OCRv5 並辨識照片……"
        lifecycleScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IOException("無法讀取照片")
                if (bitmap.width <= 0 || bitmap.height <= 0) throw IOException("照片格式無效")
                val engine = paddleOCR ?: PaddleOCR.create(this@MainActivity).also { paddleOCR = it }
                val result = engine.recognize(bitmap)
                val lines = result.results.map { it.text.trim() }.filter { it.isNotEmpty() }
                lastFullOcrText = lines.joinToString("\n")
                val found = mutableListOf<Entry>()
                val seen = mutableSetOf<String>()
                val idRegex = Regex("(?<![A-Z0-9])[A-Z][12][0-9]{8}(?![A-Z0-9])", RegexOption.IGNORE_CASE)
                val facilityRegex = Regex("(?<!\\d)\\d{10}(?!\\d)")
                val nhRegex = Regex("(?<![A-Z0-9])[A-Z]{2}\\d{6}(?:\\d{2}|[A-Z]{2}|[A-Z]\\d)(?![A-Z0-9])", RegexOption.IGNORE_CASE)
                for (line in lines) {
                    val normalized = line.uppercase(Locale.ROOT).replace(" ", "")
                    for (m in nhRegex.findAll(normalized)) addUnique(found, seen, "健保碼", m.value)
                    for (m in idRegex.findAll(normalized)) addUnique(found, seen, "身分證字號", m.value)
                    for (m in facilityRegex.findAll(normalized)) addUnique(found, seen, "醫事機構代碼", m.value)
                }
                if (found.isNotEmpty()) { entries.addAll(found); renderEntries() }
                status.text = "PP-OCRv5 完成：辨識 ${lines.size} 行，找到 ${found.size} 筆格式候選值；請人工核對。"
                showFullText()
            } catch (e: Exception) {
                status.text = "PP-OCRv5 辨識失敗：${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun addUnique(target: MutableList<Entry>, seen: MutableSet<String>, type: String, value: String) {
        val key = "$type|$value"
        if (seen.add(key)) target.add(Entry(type, value, false))
    }

    private fun renderEntries() {
        listContainer.removeAllViews()
        if (entries.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "尚無候選值。辨識後會列出符合格式的資料，並可查看完整 OCR 文字。"
                setTextColor(Color.GRAY); setPadding(0, dp(8), 0, dp(8))
            }); return
        }
        entries.forEachIndexed { index, entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.WHITE)
            }
            card.addView(TextView(this).apply {
                text = "${index + 1}. ${entry.type}" + if (entry.confirmed) " · 已確認/手動輸入" else " · 待核對"
                textSize = 14f; setTextColor(if (entry.confirmed) Color.rgb(30, 120, 70) else Color.rgb(170, 90, 20)); typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = entry.value; textSize = 21f; setTextColor(Color.rgb(20, 45, 70)); setPadding(0, dp(4), 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallButton("編輯") { editEntry(index) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
            row.addView(smallButton("↑") { moveEntry(index, -1) }, LinearLayout.LayoutParams(0, dp(40), 0.5f).apply { marginStart = dp(3); marginEnd = dp(3) })
            row.addView(smallButton("↓") { moveEntry(index, 1) }, LinearLayout.LayoutParams(0, dp(40), 0.5f).apply { marginStart = dp(3); marginEnd = dp(3) })
            row.addView(smallButton("刪除") {
                entries.removeAt(index)
                if (currentBarcodeIndex >= entries.size) currentBarcodeIndex = (entries.size - 1).coerceAtLeast(0)
                renderEntries(); if (entries.isEmpty()) barcodePanel.visibility = View.GONE
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) })
            card.addView(row)
            listContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun editEntry(index: Int) {
        val entry = entries[index]
        val input = EditText(this).apply { setText(entry.value); setSelection(text.length); setSingleLine(true) }
        val types = listOf("健保碼", "醫事機構代碼", "身分證字號", "其他文字")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(types.indexOf(entry.type).takeIf { it >= 0 } ?: 0)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0); addView(spinner); addView(input) }
        android.app.AlertDialog.Builder(this).setTitle("編輯第 ${index + 1} 筆").setView(box).setNegativeButton("取消", null)
            .setPositiveButton("儲存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) { entries[index].value = value; entries[index].type = spinner.selectedItem.toString(); entries[index].confirmed = true; renderEntries() }
            }.show()
    }
    private fun moveEntry(index: Int, offset: Int) {
        val newIndex = index + offset
        if (newIndex !in entries.indices) return
        val item = entries.removeAt(index); entries.add(newIndex, item); renderEntries()
    }
    private fun renderBarcode() {
        if (entries.isEmpty()) { barcodePanel.visibility = View.GONE; return }
        val entry = entries[currentBarcodeIndex.coerceIn(entries.indices)]
        try {
            val bitMatrix = MultiFormatWriter().encode(entry.value, BarcodeFormat.CODE_128, 1000, 260, mapOf(EncodeHintType.MARGIN to 12))
            val bitmap = Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until bitMatrix.width) for (y in 0 until bitMatrix.height) bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            barcodeImage.setImageBitmap(bitmap); counter.text = "${currentBarcodeIndex + 1} / ${entries.size}　${entry.type}"
            barcodeValue.text = entry.value; barcodePanel.visibility = View.VISIBLE; status.text = "請掃描目前這一筆；確認電腦欄位已選好。"
        } catch (e: Exception) { status.text = "無法產生條碼：${e.localizedMessage ?: "請檢查文字"}" }
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; minHeight = dp(36); setPadding(dp(2), 0, dp(2), 0); setOnClickListener { action() }
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
