package com.yjk.monitor.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.yjk.monitor.R

/** 扫二维码导入平台配置（相机实时扫 + 相册选图） */
class ScanActivity : Activity() {

    private var camera: Camera? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var hint: TextView
    private val reader = MultiFormatReader()
    @Volatile
    private var decoding = false
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )

        setContentView(buildUi())

        // 相机必须在 Surface 创建之后再打开，否则预览全黑
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (hasCameraPermission()) startCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopCamera()
            }
        })

        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 扫描框
        val box = object : View(this) {
            private val p = android.graphics.Paint().apply {
                color = 0xFF07C160.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 5f
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                val size = Math.min(width, height) * 0.66f
                val l = (width - size) / 2
                val t = (height - size) / 2
                val r = l + size
                val b = t + size
                canvas.drawRect(l, t, r, b, p)
            }
        }
        root.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        hint = TextView(this).apply {
            text = "对准二维码，自动识别"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, Ui.dp(this@ScanActivity, 40f), 0, 0)
        }
        // 撑满剩余空间，把下面的按钮推到屏幕底部
        col.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                Ui.dp(this@ScanActivity, 16f), 0,
                Ui.dp(this@ScanActivity, 16f), Ui.dp(this@ScanActivity, 36f)
            )
        }
        bar.addView(btn("相册里的二维码") { pickImage() })
        bar.addView(btn("取消") { finish() })
        col.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            col,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    private fun btn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13.5f
        setTextColor(Color.WHITE)
        background = Ui.rounded(this@ScanActivity, 0x66000000, 100f)
        setPadding(
            Ui.dp(this@ScanActivity, 16f), Ui.dp(this@ScanActivity, 9f),
            Ui.dp(this@ScanActivity, 16f), Ui.dp(this@ScanActivity, 9f)
        )
        includeFontPadding = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = Ui.dp(this@ScanActivity, 10f) }
    }

    // ---------------- 相机 ----------------

    private fun startCamera() {
        try {
            val cam = Camera.open()
            camera = cam
            val params = cam.parameters
            params.previewFormat = android.graphics.ImageFormat.NV21
            val best = params.supportedPreviewSizes
                .filter { it.width * it.height <= 1920 * 1080 }
                .maxByOrNull { it.width * it.height }
            if (best != null) params.setPreviewSize(best.width, best.height)
            cam.parameters = params
            // 竖屏预览：不设置的话画面会横着
            try {
                cam.setDisplayOrientation(90)
            } catch (e: Exception) {
                // ignore
            }
            cam.setPreviewDisplay(surfaceView.holder)
            cam.setPreviewCallback { data, c -> decodeFrame(data, c) }
            cam.startPreview()
        } catch (e: Exception) {
            hint.text = "相机打不开：" + (e.message ?: "未知原因") + "\n可点下面从相册选图"
        }
    }

    private fun stopCamera() {
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (e: Exception) {
            // ignore
        }
        camera = null
    }

    private fun decodeFrame(data: ByteArray, cam: Camera) {
        if (decoding || done) return
        decoding = true
        try {
            val size = cam.parameters.previewSize ?: return
            val w = size.width
            val h = size.height
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            var text: String? = null
            try {
                text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: Exception) {
                try {
                    val rotated = source.rotateCounterClockwise()
                    text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(rotated))).text
                } catch (e2: Exception) {
                    // 继续下一帧
                }
            } finally {
                reader.reset()
            }
            if (text != null) runOnUiThread { onFound(text) }
        } catch (e: Exception) {
            // ignore frame
        } finally {
            decoding = false
        }
    }

    // ---------------- 相册 ----------------

    private fun pickImage() {
        try {
            val i = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            i.type = "image/*"
            startActivityForResult(i, 2)
        } catch (e: Exception) {
            toast("打不开相册")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            opts.inJustDecodeBounds = false
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return
            val text = decodeBitmap(bmp)
            if (text != null) onFound(text) else toast("这张图里没识别到二维码")
        } catch (e: Exception) {
            toast("读取图片失败")
        }
    }

    private fun decodeBitmap(src: Bitmap): String? {
        var bmp = src
        val max = 1400
        val longSide = Math.max(bmp.width, bmp.height)
        if (longSide > max) {
            val scale = max.toFloat() / longSide
            bmp = Bitmap.createScaledBitmap(
                bmp,
                Math.max(1, (bmp.width * scale).toInt()),
                Math.max(1, (bmp.height * scale).toInt()),
                true
            )
        }
        // 图片可能是旋转过的，四个方向都试一遍
        for (i in 0 until 4) {
            val cur = if (i == 0) bmp else Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                android.graphics.Matrix().apply { postRotate(90f * i) }, true
            )
            val w = cur.width
            val h = cur.height
            val pixels = IntArray(w * h)
            cur.getPixels(pixels, 0, w, 0, 0, w, h)
            try {
                val text = reader.decodeWithState(
                    BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
                ).text
                if (text != null) return text
            } catch (e: Exception) {
                // 换方向再试
            } finally {
                reader.reset()
            }
        }
        return null
    }

    // ---------------- 结果 ----------------

    private fun onFound(text: String) {
        android.util.Log.d("YJK", "scan text=" + text.take(120))
        if (done) return
        done = true
        stopCamera()
        setResult(RESULT_OK, Intent().putExtra("text", text))
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (surfaceView.holder.surface.isValid) startCamera()
        } else {
            hint.text = "没有相机权限，可用下面「相册里的二维码」"
        }
    }

    override fun onResume() {
        super.onResume()
        if (camera == null && hasCameraPermission() && surfaceView.holder.surface.isValid) {
            startCamera()
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
