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
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.EngineConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * PhotoBarcodeMVP - PaddleOCR PP-OCRv5 Mobile version.
 *
 * IMPORTANT:
 * This file expects the PaddleOCR Android SDK module to be integrated as
 * implementation(project(":ppocr-sdk")) and the PP-OCRv5 Mobile ONNX model
 * assets to be installed at the locations expected by that SDK.
 */
class MainActivity : AppCompatActivity() {
    private data class Entry(
        var type: String,
        var value: String,
        var confirmed: Boolean = false
    )

    private val entries = mutableListOf<Entry>()
    private lateinit var listContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var barcodeImage: ImageView
    private lateinit var barcodeValue: TextView
    private lateinit var barcodePanel: LinearLayout
    private lateinit var counter: TextView
    private lateinit var fullTextButton: Button

    private var currentBarcodeIndex = 0
    private var pendingCameraUri: Uri? = null
    private var latestOcrText: String = ""
    private var paddleOcr: PaddleOCR? = null
    private var isRecognizing = false

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) {
                pendingCameraUri?.let { runOcr(it) }
                    ?: run { status.text = "找不到拍照圖片，請重新拍攝或選擇照片。" }
            } else {
                status.text = "已取消拍照"
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "手機照片條碼輸入器"
            textSize = 24f
            setTextColor(Color.rgb(24, 54, 84))
            typeface = Typeface.DEFAULT_BOLD
        })

        content.addView(TextView(this).apply {
            text = "PaddleOCR PP-OCRv5 本機辨識；先顯示完整文字，再整理代碼候選值。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(12))
        })

        val photoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        photoRow.addView(
            button("拍照") { launchCamera() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            }
        )
        photoRow.addView(
            button("選照片") { pickImage.launch("image/*") },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            }
        )
        content.addView(photoRow)

        status = TextView(this).apply {
            text = "請拍攝處方箋或從相簿選取照片。首次辨識需要載入 OCR 模型。"
            textSize = 14f
            setTextColor(Color.rgb(70, 85, 100))
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(status)

        fullTextButton = button("查看完整 OCR 文字") {
            showFullOcrText()
        }
        fullTextButton.isEnabled = false
        content.addView(fullTextButton)

        content.addView(TextView(this).apply {
            text = "辨識／確認資料（請人工核對後再輸出條碼）"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 54, 84))
            setPadding(0, dp(12), 0, dp(8))
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(listContainer)

        content.addView(TextView(this).apply {
            text = "手動新增"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 54, 84))
            setPadding(0, dp(16), 0, dp(6))
        })

        val typeSpinner = Spinner(this)
        typeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("健保碼", "醫事機構代碼", "身分證字號", "其他文字")
        )
        content.addView(typeSpinner)

        val manualInput = EditText(this).apply {
            hint = "輸入或修正文字"
            setSingleLine(true)
            textSize = 16f
        }
        content.addView(manualInput)

        content.addView(button("新增到條碼清單") {
            val value = manualInput.text.toString().trim()
            if (value.isBlank()) {
                status.text = "請先輸入文字"
            } else {
                entries.add(Entry(typeSpinner.selectedItem.toString(), value, true))
                manualInput.text.clear()
                renderEntries()
                status.text = "已新增，請確認順序"
            }
        })

        content.addView(button("開始條碼輸出") {
            if (entries.isEmpty()) {
                status.text = "目前沒有資料，請先辨識或手動新增"
            } else {
                currentBarcodeIndex = 0
                renderBarcode()
            }
        }.apply {
            setPadding(0, dp(8), 0, dp(8))
        })

        barcodePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }

        counter = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        barcodeValue = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(0, dp(8), 0, dp(8))
        }

        barcodeImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Code 128 條碼"
        }

        barcodePanel.addView(counter)
        barcodePanel.addView(barcodeImage, LinearLayout.LayoutParams(-1, dp(180)))
        barcodePanel.addView(barcodeValue)
        barcodePanel.addView(TextView(this).apply {
            text = "請讓掃描器只讀取畫面中央的這一個條碼。掃描完成後再按下一筆。"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        })

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        nav.addView(
            button("上一筆") {
                if (currentBarcodeIndex > 0) {
                    currentBarcodeIndex--
                    renderBarcode()
                }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(4)
            }
        )
        nav.addView(
            button("下一筆") {
                if (currentBarcodeIndex < entries.lastIndex) {
                    currentBarcodeIndex++
                    renderBarcode()
                } else {
                    status.text = "已到最後一筆"
                }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(4)
            }
        )
        barcodePanel.addView(nav)
        content.addView(barcodePanel)

        root.addView(TextView(this).apply {
            text = "注意：OCR 結果及格式符合都不代表資料正確；掃描前請人工核對。"
            textSize = 12f
            setTextColor(Color.rgb(160, 65, 35))
            setPadding(0, dp(8), 0, 0)
        })

        setContentView(root)
    }

    private fun launchCamera() {
        try {
            val uri = createCameraUri()
            pendingCameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            status.text = "無法開啟相機，請改用「選照片」。${e.message ?: ""}"
        }
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
        if (isRecognizing) return
        isRecognizing = true
        status.text = "正在載入 PP-OCRv5 並辨識照片……"
        fullTextButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "無法讀取照片" }
                        BitmapFactory.decodeStream(input)
                            ?: throw IllegalArgumentException("照片解碼失敗")
                    }
                }

                val resultText = withContext(Dispatchers.Default) {
                    val engine = paddleOcr ?: PaddleOCR.create(
                        applicationContext,
                        PaddleOCRConfig(
                            detLimitSideLen = 960,
                            detLimitType = "max",
                            detMaxSideLimit = 4000,
                            detBoxThresh = 0.5f,
                            recScoreThresh = 0.0f,
                            recBatchSize = 1
                        ),
                        EngineConfig(numThreads = 4)
                    ).also { paddleOcr = it }

                    val result = engine.recognize(bitmap)
                    result.results.joinToString("\n") { item ->
                        val score = String.format(Locale.ROOT, "%.2f", item.confidence)
                        "${item.text}\t[${score}]"
                    }
                }

                latestOcrText = resultText
                    .lines()
                    .map { it.substringBefore("\t[") }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")

                fullTextButton.isEnabled = latestOcrText.isNotBlank()
                val found = extractCandidates(latestOcrText)
                if (found.isEmpty()) {
                    status.text =
                        "OCR 完成，辨識 ${latestOcrText.lines().count { it.isNotBlank() }} 行文字，但沒有找到可自動整理的代碼。請查看完整文字或手動新增。"
                } else {
                    val existing = entries.map { "${it.type}|${it.value}" }.toMutableSet()
                    var added = 0
                    found.forEach { entry ->
                        if (existing.add("${entry.type}|${entry.value}")) {
                            entries.add(entry)
                            added++
                        }
                    }
                    renderEntries()
                    status.text =
                        "PP-OCRv5 辨識完成，共 ${latestOcrText.lines().count { it.isNotBlank() }} 行文字；新增 ${added} 筆候選值。請逐筆核對。"
                }
            } catch (e: Exception) {
                status.text = "PaddleOCR 辨識失敗：${e.localizedMessage ?: e.javaClass.simpleName}。請確認 SDK 與 PP-OCRv5 模型已整合。"
            } finally {
                isRecognizing = false
            }
        }
    }

    /**
     * Only auto-classify likely values. Full OCR text remains available separately.
     * Ten-digit strings are treated as facility codes only when the line has a
     * medical-institution label, avoiding the old behavior of classifying every
     * ten-digit number as a facility code.
     */
    private fun extractCandidates(text: String): List<Entry> {
        val found = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        val lines = text.lines()

        val nhRegex = Regex(
            "(?<![A-Z0-9])[A-Z]{2}\\d{6}(?:\\d{2}|[A-Z]{2}|[A-Z]\\d)(?![A-Z0-9])",
            RegexOption.IGNORE_CASE
        )
        val idRegex = Regex(
            "(?<![A-Z0-9])[A-Z][12]\\d{8}(?!\\d)",
            RegexOption.IGNORE_CASE
        )
        val maskedIdRegex = Regex(
            "(?<![A-Z0-9])[A-Z][12]\\d{5,7}(?:\\*{1,4}|X{1,4})(?![A-Z0-9])",
            RegexOption.IGNORE_CASE
        )
        val facilityRegex = Regex("(?<!\\d)\\d{10}(?!\\d)")
        val facilityLabelRegex = Regex("醫事機構代碼|醫療院所代碼|醫事機構|醫療院所")

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.uppercase(Locale.ROOT).replace(" ", "").replace("：", ":")
            nhRegex.findAll(line).forEach { addCandidate(found, seen, "健保碼", it.value) }

            if (line.contains("身分證號") || line.contains("身分證字號") || line.contains("身份證號")) {
                idRegex.findAll(line).forEach { addCandidate(found, seen, "身分證字號", it.value) }
                maskedIdRegex.findAll(line).forEach { addCandidate(found, seen, "身分證字號", it.value) }
                if (facilityRegex.containsMatchIn(line)) {
                    // Don't classify the ID field's digits as a facility code.
                }
            }

            if (facilityLabelRegex.containsMatchIn(line)) {
                facilityRegex.findAll(line).forEach {
                    addCandidate(found, seen, "醫事機構代碼", it.value)
                }
                // OCR sometimes puts the field label and its value on adjacent lines.
                if (facilityRegex.findAll(line).none()) {
                    for (nextIndex in (index + 1)..minOf(index + 2, lines.lastIndex)) {
                        facilityRegex.findAll(lines[nextIndex].replace(" ", "")).forEach {
                            addCandidate(found, seen, "醫事機構代碼", it.value)
                        }
                    }
                }
            } else {
                // An unmasked ID may appear without its label, but only accept the
                // strict Taiwan ID pattern; masked IDs are surfaced when labelled.
                idRegex.findAll(line).forEach {
                    addCandidate(found, seen, "身分證字號", it.value)
                }
            }
        }
        return found
    }

    private fun addCandidate(
        target: MutableList<Entry>,
        seen: MutableSet<String>,
        type: String,
        value: String
    ) {
        val clean = value.trim().uppercase(Locale.ROOT)
        val key = "$type|$clean"
        if (clean.isNotBlank() && seen.add(key)) {
            target.add(Entry(type, clean, false))
        }
    }

    private fun showFullOcrText() {
        if (latestOcrText.isBlank()) {
            status.text = "尚無 OCR 文字，請先選取或拍攝照片。"
            return
        }

        val textView = TextView(this).apply {
            text = latestOcrText
            textSize = 15f
            setTextColor(Color.rgb(30, 45, 60))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("完整 OCR 文字（可長按選取複製）")
            .setView(scroll)
            .setPositiveButton("關閉", null)
            .setNeutralButton("複製全部") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("OCR 文字", latestOcrText)
                )
                status.text = "完整 OCR 文字已複製"
            }
            .show()
    }

    private fun renderEntries() {
        listContainer.removeAllViews()
        if (entries.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "尚無候選值。辨識後會整理可能的代碼；完整 OCR 文字可另外查看。"
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        entries.forEachIndexed { index, entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.WHITE)
            }

            card.addView(TextView(this).apply {
                text = "${index + 1}. ${entry.type}" +
                    if (entry.confirmed) " · 已確認/手動輸入" else " · 待核對"
                textSize = 14f
                setTextColor(
                    if (entry.confirmed) Color.rgb(30, 120, 70)
                    else Color.rgb(170, 90, 20)
                )
                typeface = Typeface.DEFAULT_BOLD
            })

            card.addView(TextView(this).apply {
                text = entry.value
                textSize = 21f
                setTextColor(Color.rgb(20, 45, 70))
                setPadding(0, dp(4), 0, dp(8))
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(
                smallButton("編輯") { editEntry(index) },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) }
            )
            row.addView(
                smallButton("↑") { moveEntry(index, -1) },
                LinearLayout.LayoutParams(0, dp(40), 0.5f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
            row.addView(
                smallButton("↓") { moveEntry(index, 1) },
                LinearLayout.LayoutParams(0, dp(40), 0.5f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
            row.addView(
                smallButton("刪除") {
                    entries.removeAt(index)
                    if (currentBarcodeIndex >= entries.size) {
                        currentBarcodeIndex = (entries.size - 1).coerceAtLeast(0)
                    }
                    renderEntries()
                    if (entries.isEmpty()) barcodePanel.visibility = View.GONE
                },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) }
            )
            card.addView(row)
            listContainer.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )
        }
    }

    private fun editEntry(index: Int) {
        if (index !in entries.indices) return
        val entry = entries[index]
        val input = EditText(this).apply {
            setText(entry.value)
            setSelection(text.length)
            setSingleLine(true)
        }
        val types = listOf("健保碼", "醫事機構代碼", "身分證字號", "其他文字")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                types
            )
            setSelection(types.indexOf(entry.type).takeIf { it >= 0 } ?: 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(spinner)
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("編輯第 ${index + 1} 筆")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty() && index in entries.indices) {
                    entries[index].value = value
                    entries[index].type = spinner.selectedItem.toString()
                    entries[index].confirmed = true
                    renderEntries()
                }
            }
            .show()
    }

    private fun moveEntry(index: Int, offset: Int) {
        val newIndex = index + offset
        if (index !in entries.indices || newIndex !in entries.indices) return
        val item = entries.removeAt(index)
        entries.add(newIndex, item)
        renderEntries()
    }

    private fun renderBarcode() {
        if (entries.isEmpty()) {
            barcodePanel.visibility = View.GONE
            return
        }

        val entry = entries[currentBarcodeIndex.coerceIn(entries.indices)]
        try {
            val hints = mapOf(EncodeHintType.MARGIN to 12)
            val bitMatrix = MultiFormatWriter().encode(
                entry.value,
                BarcodeFormat.CODE_128,
                1000,
                260,
                hints
            )
            val bitmap = Bitmap.createBitmap(
                bitMatrix.width,
                bitMatrix.height,
                Bitmap.Config.ARGB_8888
            )
            for (x in 0 until bitMatrix.width) {
                for (y in 0 until bitMatrix.height) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                    )
                }
            }

            barcodeImage.setImageBitmap(bitmap)
            counter.text = "${currentBarcodeIndex + 1} / ${entries.size}　${entry.type}"
            barcodeValue.text = entry.value
            barcodePanel.visibility = View.VISIBLE
            status.text = "請掃描目前這一筆；確認電腦欄位已選好。"
        } catch (e: Exception) {
            status.text = "無法產生條碼：${e.localizedMessage ?: "請檢查文字"}"
        }
    }

    override fun onDestroy() {
        val engine = paddleOcr
        paddleOcr = null
        if (engine != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    engine.release()
                } catch (_: Exception) {
                    // Best-effort native resource release.
                }
            }
        }
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minHeight = dp(36)
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
