package com.pharmacy.photobarcodemvp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.EngineConfig
import org.opencv.android.OpenCVLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * PhotoBarcodeMVP - PaddleOCR PP-OCRv5 Mobile version.
 * OpenCV JNI is initialized before PaddleOCR is used.
 */
class MainActivity : AppCompatActivity() {
    private data class KeyStroke(val modifiers: Int, val keyCode: Int)

    private lateinit var status: TextView
    private lateinit var fullTextButton: Button
    private lateinit var manualInput: EditText

    private var pendingCameraUri: Uri? = null
    private var latestOcrText: String = ""
    private var paddleOcr: PaddleOCR? = null
    private var isRecognizing = false

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private var hidRegistered = false
    private var hidRegistrationRequested = false
    private var pendingPairing = false

    private val bluetoothPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) startHidRegistration(pendingPairing)
            else status.text = "需要附近裝置權限才能使用藍牙鍵盤。"
        }

    private val enableBluetoothRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startHidRegistration(pendingPairing)
            else status.text = "藍牙未啟用，無法連線電腦。"
        }

    private val discoverableRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            status.text = if (result.resultCode == RESULT_OK) {
                "手機目前可被電腦搜尋。請在電腦的藍牙設定中新增「PhotoBarcode Keyboard」，配對後按「連線電腦」。"
            } else "已取消配對。"
        }

    private val hidProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            registerHidKeyboard()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                connectedHost = null
                hidRegistered = false
                status.text = "藍牙鍵盤連線中斷。"
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
            hidRegistered = registered
            hidRegistrationRequested = false
            if (!registered) {
                status.text = "藍牙鍵盤尚未註冊。"
                return
            }
            status.text = "藍牙鍵盤已就緒。請配對或連線電腦。"
            if (pendingPairing) makePhoneDiscoverable()
            else refreshConnectedHost()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedHost = device
                status.text = "已連線到 " + (device.name ?: "電腦") + "。請在電腦選好輸入欄位後發送。"
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedHost?.address == device.address) connectedHost = null
                status.text = "電腦已中斷連線。"
            }
        }
    }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) {
                pendingCameraUri?.let { runOcr(it) }
                    ?: run { status.text = "找不到拍照圖片，請重新拍攝或選擇照片。" }
            } else status.text = "已取消拍照"
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) runOcr(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private lateinit var ocrTextView: TextView

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(244, 248, 252))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "手機照片條碼輸入器"
            textSize = 23f
            setTextColor(Color.rgb(24, 54, 84))
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "先連線電腦，再拍照或選照片。長按 OCR 文字選取英數內容，或在下方手動輸入。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(5), 0, dp(8))
        })

        val bluetoothRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bluetoothRow.addView(button("配對電腦") { prepareKeyboard(pairing = true) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        bluetoothRow.addView(button("連線電腦") { prepareKeyboard(pairing = false) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        content.addView(bluetoothRow)

        val photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        photoRow.addView(button("拍照") { launchCamera() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        photoRow.addView(button("選照片") { pickImage.launch("image/*") },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        content.addView(photoRow)

        status = TextView(this).apply {
            text = "尚未連線。請先配對並連線電腦。"
            textSize = 14f
            setTextColor(Color.rgb(70, 85, 100))
            setPadding(0, dp(8), 0, dp(6))
        }
        content.addView(status)

        content.addView(TextView(this).apply {
            text = "OCR 全文（長按選取）"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 54, 84))
            setPadding(0, dp(5), 0, dp(5))
        })
        ocrTextView = TextView(this).apply {
            text = "拍照或選擇照片後，OCR 文字會顯示在這裡。"
            textSize = 16f
            setTextColor(Color.rgb(30, 45, 60))
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val ocrScroll = ScrollView(this).apply { addView(ocrTextView) }
        content.addView(ocrScroll, LinearLayout.LayoutParams(-1, dp(250)))

        content.addView(TextView(this).apply {
            text = "手動輸入／選取文字"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 54, 84))
            setPadding(0, dp(9), 0, dp(4))
        })
        manualInput = EditText(this).apply {
            hint = "點擊輸入文字，開啟 Android 原生鍵盤"
            setSingleLine(true)
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        content.addView(manualInput, LinearLayout.LayoutParams(-1, dp(52)))

        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyRow.addView(button("Tab\n下一欄") { sendControlKey(0x2B, "Tab") },
            LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(4) })
        keyRow.addView(button("Enter\n確認") { sendControlKey(0x28, "Enter") },
            LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginHorizontal = dp(4) })
        keyRow.addView(button("Backspace\n刪除一字") { sendControlKey(0x2A, "Backspace") },
            LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(4) })
        content.addView(keyRow)

        content.addView(button("傳送到電腦") {
            val selected = selectedText(ocrTextView)
            val value = if (selected.isNotBlank()) selected else manualInput.text.toString()
            sendTextToComputer(value, clearInputOnSuccess = true)
        }.apply { textSize = 18f }, LinearLayout.LayoutParams(-1, dp(56)).apply {
            topMargin = dp(6)
        })
        content.addView(TextView(this).apply {
            text = "傳送成功後會清空輸入框；不會自動按 Enter 或 Tab。"
            textSize = 12f
            setTextColor(Color.rgb(130, 70, 35))
            setPadding(0, dp(6), 0, dp(3))
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
            var bitmap: Bitmap? = null
            try {
                bitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "無法讀取照片" }
                        BitmapFactory.decodeStream(input) ?: throw IllegalArgumentException("照片解碼失敗")
                    }
                }
                val image = requireNotNull(bitmap) { "照片解碼失敗" }
                val resultText = withContext(Dispatchers.Default) {
                    // Fix for UnsatisfiedLinkError: load OpenCV native JNI before PaddleOCR uses Mat.
                    if (!OpenCVLoader.initDebug()) {
                        throw IllegalStateException(
                            "OpenCV 原生程式庫載入失敗。APK 可能缺少相容的 libopencv_java4.so。"
                        )
                    }
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
                        EngineConfig(numThreads = 2)
                    ).also { paddleOcr = it }

                    val result = engine.recognize(image)
                    result.results.joinToString("\n") { item ->
                        val score = String.format(Locale.ROOT, "%.2f", item.confidence)
                        "${item.text}\t[${score}]"
                    }
                }
                latestOcrText = resultText.lines().map { it.substringBefore("\t[") }
                    .filter { it.isNotBlank() }.joinToString("\n")
                fullTextButton.isEnabled = latestOcrText.isNotBlank()
                ocrTextView.text = latestOcrText
                status.text = "PP-OCRv5 辨識完成，共 " + latestOcrText.lines().count { it.isNotBlank() } + " 行文字。請選取要輸入電腦的文字。"
            } catch (e: Throwable) {
                status.text = "PaddleOCR 辨識失敗：${e.localizedMessage ?: e.javaClass.simpleName}。請確認 OpenCV 原生程式庫、SDK 與 PP-OCRv5 模型已整合。"
            } finally {
                bitmap?.recycle()
                isRecognizing = false
            }
        }
    }

    private fun showFullOcrText() {
        if (latestOcrText.isBlank()) {
            status.text = "尚無 OCR 文字，請先選取或拍攝照片。"
            return
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.rgb(244, 248, 252))
        }
        page.addView(TextView(this).apply {
            text = "選取要輸入電腦的文字"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 54, 84))
        })
        page.addView(TextView(this).apply {
            text = "長按 OCR 文字並拖曳選取，再按「發送選取文字」。請先在電腦點選正確欄位。"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(5), 0, dp(8))
        })

        val selectedPreview = TextView(this).apply {
            text = "尚未選取文字"
            textSize = 14f
            setTextColor(Color.rgb(30, 90, 150))
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(Color.WHITE)
        }
        page.addView(selectedPreview, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
        })

        val ocrTextView = TextView(this).apply {
            text = latestOcrText
            textSize = 17f
            setTextColor(Color.rgb(30, 45, 60))
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val textScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(ocrTextView)
        }
        page.addView(textScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(button("讀取選取") {
            selectedPreview.text = selectedText(ocrTextView).ifBlank {
                "尚未選取文字：請長按 OCR 文字並拖曳選取"
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        actionRow.addView(button("發送選取文字") {
            val selected = selectedText(ocrTextView)
            if (selected.isBlank()) {
                Toast.makeText(this, "請先長按並選取文字", Toast.LENGTH_SHORT).show()
            } else {
                selectedPreview.text = "準備發送：" + selected
                sendTextToComputer(selected)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        page.addView(actionRow)

        var selectionDialog: android.app.Dialog? = null
        page.addView(button("完成，返回") { selectionDialog?.dismiss() },
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(5) })

        val dialog = android.app.Dialog(this)
        selectionDialog = dialog
        dialog.setContentView(page)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun selectedText(view: TextView): String {
        val start = view.selectionStart
        val end = view.selectionEnd
        return if (start >= 0 && end >= 0 && start != end) {
            view.text.subSequence(minOf(start, end), maxOf(start, end)).toString()
        } else ""
    }

    override fun onResume() {
        super.onResume()
        if (hidDevice != null && !hidRegistered && !hidRegistrationRequested &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        ) {
            registerHidKeyboard()
        }
    }

    override fun onDestroy() {
        val engine = paddleOcr
        paddleOcr = null
        if (engine != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try { engine.release() } catch (_: Throwable) { /* best effort */ }
            }
        }
        try {
            hidDevice?.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (_: SecurityException) {
            // Bluetooth permission can be revoked while the activity is open.
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun prepareKeyboard(pairing: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            status.text = "藍牙鍵盤模式需要 Android 9 或更新版本。"
            return
        }
        pendingPairing = pairing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
            if (pairing) needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                bluetoothPermissionRequest.launch(missing.toTypedArray())
                return
            }
        }
        startHidRegistration(pairing)
    }

    @SuppressLint("MissingPermission")
    private fun startHidRegistration(pairing: Boolean) {
        pendingPairing = pairing
        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager?.adapter
        val adapter = bluetoothAdapter
        if (adapter == null) {
            status.text = "這台手機沒有藍牙功能。"
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (hidDevice != null) {
            if (hidRegistered) {
                if (pairing) makePhoneDiscoverable() else showPairedHosts()
            }
            return
        }
        status.text = "正在準備藍牙鍵盤……"
        if (!adapter.getProfileProxy(this, hidProfileListener, BluetoothProfile.HID_DEVICE)) {
            status.text = "手機系統不支援藍牙 HID 鍵盤。"
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidKeyboard() {
        val descriptor = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
            0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08,
            0x81.toByte(), 0x01, 0x95.toByte(), 0x05, 0x75, 0x01,
            0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x03, 0x91.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte()
        )
        val settings = BluetoothHidDeviceAppSdpSettings(
            "PhotoBarcode Keyboard",
            "OCR text input keyboard",
            "Photobarcode",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            descriptor
        )
        hidRegistrationRequested = true
        val registered = hidDevice?.registerApp(settings, null, null, mainExecutor, hidCallback) == true
        if (!registered) {
            hidRegistrationRequested = false
            status.text = "無法註冊藍牙鍵盤，請確認手機支援 HID Device。"
        }
    }

    @SuppressLint("MissingPermission")
    private fun makePhoneDiscoverable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "需要「附近裝置」權限，才能讓電腦找到手機鍵盤。"
            return
        }
        status.text = "正在開啟手機配對模式……"
        discoverableRequest.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnectedHost() {
        connectedHost = try {
            hidDevice?.connectedDevices?.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun showPairedHosts() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            status.text = "請先啟用藍牙。"
            return
        }
        val hosts = try {
            adapter.bondedDevices.toList()
        } catch (_: SecurityException) {
            emptyList()
        }
        if (hosts.isEmpty()) {
            status.text = "尚未配對電腦。請按「配對電腦」，再從電腦的藍牙設定新增手機。"
            return
        }
        val labels = hosts.map { it.name ?: it.address }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("選擇已配對的電腦")
            .setItems(labels) { _, which ->
                val target = hosts[which]
                status.text = "正在連線到 " + (target.name ?: "電腦") + "……"
                val started = try {
                    hidDevice?.connect(target) == true
                } catch (_: SecurityException) {
                    false
                }
                if (!started) status.text = "連線要求未送出。請確認電腦已完成配對後再試。"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun keyStrokeFor(char: Char): KeyStroke? {
        if (char in 'a'..'z') return KeyStroke(0, 0x04 + (char - 'a'))
        if (char in 'A'..'Z') return KeyStroke(0x02, 0x04 + (char - 'A'))
        if (char in '1'..'9') return KeyStroke(0, 0x1E + (char - '1'))
        if (char == '0') return KeyStroke(0, 0x27)
        return when (char) {
            ' ' -> KeyStroke(0, 0x2C)
            '-' -> KeyStroke(0, 0x2D)
            '_' -> KeyStroke(0x02, 0x2D)
            '=' -> KeyStroke(0, 0x2E)
            '+' -> KeyStroke(0x02, 0x2E)
            '[' -> KeyStroke(0, 0x2F)
            '{' -> KeyStroke(0x02, 0x2F)
            ']' -> KeyStroke(0, 0x30)
            '}' -> KeyStroke(0x02, 0x30)
            '\\' -> KeyStroke(0, 0x31)
            '|' -> KeyStroke(0x02, 0x31)
            ';' -> KeyStroke(0, 0x33)
            ':' -> KeyStroke(0x02, 0x33)
            '\'' -> KeyStroke(0, 0x34)
            '"' -> KeyStroke(0x02, 0x34)
            '`' -> KeyStroke(0, 0x35)
            '~' -> KeyStroke(0x02, 0x35)
            ',' -> KeyStroke(0, 0x36)
            '<' -> KeyStroke(0x02, 0x36)
            '.' -> KeyStroke(0, 0x37)
            '>' -> KeyStroke(0x02, 0x37)
            '/' -> KeyStroke(0, 0x38)
            '?' -> KeyStroke(0x02, 0x38)
            '!' -> KeyStroke(0x02, 0x1E)
            '@' -> KeyStroke(0x02, 0x1F)
            '#' -> KeyStroke(0x02, 0x20)
            '$' -> KeyStroke(0x02, 0x21)
            '%' -> KeyStroke(0x02, 0x22)
            '^' -> KeyStroke(0x02, 0x23)
            '&' -> KeyStroke(0x02, 0x24)
            '*' -> KeyStroke(0x02, 0x25)
            '(' -> KeyStroke(0x02, 0x26)
            ')' -> KeyStroke(0x02, 0x27)
            else -> null
        }
    }

    private fun sendControlKey(keyCode: Int, label: String) {
        val host = connectedHost
        val keyboard = hidDevice
        if (!hidRegistered || keyboard == null || host == null) {
            status.text = "尚未連線電腦，無法傳送 $label。"
            return
        }
        lifecycleScope.launch {
            try {
                check(keyboard.sendReport(host, 0, byteArrayOf(0, 0, keyCode.toByte(), 0, 0, 0, 0, 0))) {
                    "$label 傳送失敗"
                }
                delay(20)
                check(keyboard.sendReport(host, 0, ByteArray(8))) { "$label 按鍵釋放失敗" }
                status.text = "已傳送 $label。"
            } catch (e: Exception) {
                status.text = "$label 傳送失敗：" + (e.localizedMessage ?: "請確認電腦仍保持連線。")
            }
        }
    }

    private fun sendTextToComputer(text: String, clearInputOnSuccess: Boolean = false) {
        val value = text.trim()
        if (value.isEmpty()) {
            status.text = "請先選取 OCR 英數文字或在輸入框輸入內容。"
            return
        }
        val strokes = value.map { keyStrokeFor(it) }
        if (strokes.any { it == null }) {
            status.text = "文字含有不支援的字元；請使用英文字母、數字、空格及常見符號。"
            return
        }
        val host = connectedHost
        val keyboard = hidDevice
        if (!hidRegistered || keyboard == null || host == null) {
            status.text = "尚未連線電腦。請先配對並按「連線電腦」。"
            return
        }
        lifecycleScope.launch {
            status.text = "正在輸入到 " + (host.name ?: "電腦") + "……"
            try {
                strokes.filterNotNull().forEach { stroke ->
                    val pressed = byteArrayOf(stroke.modifiers.toByte(), 0, stroke.keyCode.toByte(), 0, 0, 0, 0, 0)
                    check(keyboard.sendReport(host, 0, pressed)) { "藍牙鍵盤傳送失敗" }
                    delay(12)
                    check(keyboard.sendReport(host, 0, ByteArray(8))) { "藍牙鍵盤釋放按鍵失敗" }
                    delay(12)
                }
                if (clearInputOnSuccess) manualInput.setText("")
                status.text = "已傳送 " + value.length + " 個字元。"
            } catch (e: Exception) {
                status.text = "傳送失敗：" + (e.localizedMessage ?: "請確認電腦仍保持連線。")
            }
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
