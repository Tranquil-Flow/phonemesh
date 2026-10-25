package org.phonemesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.phonemesh.core.hub.HubCard

/**
 * "Scanner un code" (plan step 11): Android's own camera (Camera2, no AndroidX) feeding ZXing. The camera permission is
 * asked here, when the person taps scan, with its own sentence when refused. The first Phonemesh code read ends the
 * screen: a QR code from the phone in front starts a session at once ([Accounts.scanned]).
 */
class ScanActivity : ScreenActivity() {
    private lateinit var preview: TextureView
    private var thread: HandlerThread? = null
    private var closeCamera: (() -> Unit)? = null
    private var closeSession: (() -> Unit)? = null
    private var reader: ImageReader? = null
    private var shownSurface: Surface? = null
    private var attempt: CameraCallbacks? = null
    private var resumed = false
    private var cameraFailed = false
    private lateinit var hint: android.widget.TextView
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(), 0, pad(), pad()) }
        column.addView(backBar(getString(R.string.add_scan)))
        column.addView(noteLine())
        column.addView(Ui.text(this, getString(R.string.scan_hint), palette.text, 20f, bold = true).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { hint = it })
        preview = TextureView(this).apply { tag = "scan_preview" }
        column.addView(FrameLayout(this).apply { addView(preview) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = pad() })
        show(column)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!cameraFailed && !done) startCamera(this)
    }

    private fun prepareCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        if (preview.isAvailable) open() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = open()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { close(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
        }
    }

    override fun onPause() {
        resumed = false
        preview.surfaceTextureListener = null
        close()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            core.note(UiText(R.string.camera_denied))
            finish()
        }
    }

    override fun render() = Unit

    /** Main-thread boundary shared by Camera2 callbacks and controlled instrumentation fixtures. */
    internal inner class CameraCallbacks {
        private fun current() = resumed && !cameraFailed && !done && !isFinishing && attempt === this

        fun opened(dispose: () -> Unit, configure: () -> Unit) {
            if (!current()) { runCatching(dispose); return }
            closeCamera = dispose
            protect(configure)
        }
        fun configured(dispose: () -> Unit, repeat: () -> Unit) {
            if (!current()) { runCatching(dispose); return }
            closeSession = dispose
            protect(repeat)
        }
        fun disconnected(dispose: () -> Unit) = error(dispose)
        fun error(dispose: () -> Unit) {
            if (!current()) { runCatching(dispose); return }
            if (closeCamera == null) closeCamera = dispose
            fail()
        }
        fun configureFailed(dispose: () -> Unit) {
            if (!current()) { runCatching(dispose); return }
            closeSession = dispose
            fail()
        }
        fun frame(text: String) { if (current()) onRead(text) }
        fun fail() {
            if (!current()) return
            cameraFailed = true
            close()
            hint.text = getString(R.string.scan_camera_unavailable)
        }
        fun protect(operation: () -> Unit) {
            try { operation() } catch (_: Exception) { fail() }
        }
    }

    internal fun beginCameraAttempt(openCamera: (CameraCallbacks) -> Unit) {
        if (!resumed || cameraFailed || done || isFinishing || attempt != null) return
        val callbacks = CameraCallbacks().also { attempt = it }
        callbacks.protect { openCamera(callbacks) }
    }

    @SuppressLint("MissingPermission")
    private fun open() = beginCameraAttempt { callbacks ->
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: throw IllegalStateException("No camera available")
        val worker = HandlerThread("scan").also { it.start(); thread = it }
        val handler = Handler(worker.looper)
        val callbackHandler = Handler(mainLooper)
        val images = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2).also { reader = it }
        images.setOnImageAvailableListener({ r ->
            try {
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                    val text = decode(bytes, plane.rowStride, image.width, image.height)
                    if (text != null && !done) runOnUiThread { callbacks.frame(text) }
                } finally {
                    image.close()
                }
            } catch (_: Exception) {
                // ImageReader can close while a worker callback is already in flight.
                runOnUiThread { callbacks.fail() }
            }
        }, handler)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = callbacks.opened(device::close) {
                val texture = checkNotNull(preview.surfaceTexture) { "Preview surface lost" }
                texture.setDefaultBufferSize(WIDTH, HEIGHT)
                val shown = Surface(texture).also { shownSurface = it }
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(shown, images.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) = callbacks.configured(s::close) {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(shown); addTarget(images.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        s.setRepeatingRequest(request, null, handler)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) = callbacks.configureFailed(s::close)
                }, callbackHandler)
            }
            override fun onDisconnected(device: CameraDevice) = callbacks.disconnected(device::close)
            override fun onError(device: CameraDevice, error: Int) = callbacks.error(device::close)
        }, callbackHandler)
    }

    private fun onRead(text: String) {
        if (done) return
        done = true
        done = handleRead(this, core, text)
    }

    private fun close() {
        // Invalidate before releasing handles: late callbacks may only dispose their own resource.
        attempt = null
        val endSession = closeSession.also { closeSession = null }
        val endCamera = closeCamera.also { closeCamera = null }
        val images = reader.also { reader = null }
        val shown = shownSurface.also { shownSurface = null }
        val worker = thread.also { thread = null }
        runCatching { endSession?.invoke() }
        runCatching { endCamera?.invoke() }
        runCatching { images?.close() }
        runCatching { shown?.release() }
        runCatching { worker?.quitSafely() }
    }

    companion object {
        /** Internal fixture boundary before permissions or Camera2; restored by each test. */
        internal var startCamera: (ScanActivity) -> Unit = { it.prepareCamera() }

        /** Post-decoder routing, shared with silent tests that never open a camera or permission prompt. */
        internal fun handleRead(activity: android.app.Activity, core: AppCore, text: String): Boolean {
            // A hub's QR (accounts step 16) is trusted because it was scanned here, in person; a link never carries one.
            HubCard.parse(text)?.let { card ->
                core.useHub(card)
                core.note(UiText(R.string.hub_saved))
                activity.finish()
                return true
            }
            when (val added = core.accountsFlow.scanned(text)) {
                is Accounts.Added.Contact -> {
                    val name = core.history.contactRow(added.account.toString())?.name.orEmpty()
                    core.note(UiText(if (added.met) R.string.scan_added_met else R.string.scan_added, listOf(name)))
                    if (added.met) activity.startActivity(Intent(activity, ChatsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    else SafetyActivity.open(activity, added.account)
                }
                Accounts.Added.ShowYoursFirst -> core.note(UiText(R.string.scan_show_yours_first))
                Accounts.Added.Own -> core.note(UiText(R.string.add_own_code))
                Accounts.Added.Bad -> { core.note(UiText(R.string.add_bad_code)); return false }
                Accounts.Added.NotSaved -> { core.note(UiText(R.string.mail_save_failed)); return false }
            }
            activity.finish()
            return true
        }

        private const val REQUEST_CAMERA = 51
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

        /** A QR code's text in one camera frame's brightness plane; null when none is readable. */
        fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? = runCatching {
            val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        }.getOrNull()
    }
}
