package org.phonemesh.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil

/**
 * Take a photo with the phone's camera app or pick one with Android's own picker (neither needs a permission), see
 * exactly what the other phone will get, choose a size, then Send. Nothing goes out before Send. Private chats only
 * (AppCore.canSendPictures). Every failure says what happened.
 */
class PictureActivity : ScreenActivity() {
    private val chatId by lazy { intent.getLongExtra(EXTRA_CHAT_ID, -1L) }
    private var uri: Uri? = null
    private var source: Bitmap? = null
    private var prepared: PreparedPicture? = null
    private var size = PictureSize.MEDIUM
    private var look = Look.COLOUR
    private var job = 0
    private lateinit var preview: ImageView
    private lateinit var info: TextView
    private lateinit var heavy: TextView
    private lateinit var details: TextView
    private lateinit var sizes: LinearLayout
    private lateinit var looks: LinearLayout
    private lateinit var sendButton: Button
    private lateinit var choices: View

    private enum class Look(val label: Int) { COLOUR(R.string.picture_colour), GREY(R.string.picture_grey), SKETCH(R.string.picture_sketch) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!core.canSendPictures(chatId)) { finish(); return }
        @Suppress("DEPRECATION")
        uri = savedInstanceState?.getParcelable(STATE_URI) ?: intent.getParcelableExtra(EXTRA_PHOTO)
        savedInstanceState?.getString(STATE_SIZE)?.let { size = PictureSize.valueOf(it) }
        savedInstanceState?.getString(STATE_LOOK)?.let { look = Look.valueOf(it) }
        build()
        if (uri != null) load() else say(R.string.picture_start, bad = false)
    }

    /** The camera app writes a new photo into this app's own file (CaptureProvider): nothing goes to the gallery. */
    private fun takePhoto() {
        CaptureProvider.fileIn(this).delete()
        val out = CaptureProvider.uri(this)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, out)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("", out)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CAMERA)
        } catch (e: ActivityNotFoundException) {
            say(R.string.picture_no_camera)
        } catch (e: SecurityException) {
            Log.w(TAG, "camera refused", e)
            say(R.string.picture_no_camera)
        }
    }

    private fun choosePhoto() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_PICK)
        } catch (e: ActivityNotFoundException) {
            say(R.string.picture_no_gallery)
        }
    }

    /** One sentence under the picture: red for a problem, plain for a step. */
    private fun say(text: Int, bad: Boolean = true) {
        info.text = getString(text)
        info.setTextColor(if (bad) palette.bad else palette.text)
    }

    private fun build() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.picture_title)))
        column.addView(noteLine())
        preview = ImageView(this).apply {
            tag = "picture_preview"
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            contentDescription = getString(R.string.picture_label)
            background = Ui.outline(this@PictureActivity, palette.surface, palette.outline)
        }
        column.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 260)))
        info = Ui.text(this, getString(R.string.picture_working), palette.text, bold = true).apply {
            tag = "picture_time"
            setPadding(0, pad() / 2, 0, 0)
        }
        column.addView(info)
        heavy = Ui.text(this, getString(R.string.picture_heavy), palette.bad, bold = true).apply {
            tag = "picture_heavy"
            visibility = View.GONE
        }
        column.addView(heavy)
        choices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad() / 2, 0, 0)
            addView(Ui.button(this@PictureActivity, palette, getString(R.string.picture_take), R.drawable.ic_camera).apply {
                tag = "picture_take"
                setOnClickListener { takePhoto() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = pad() / 2
            })
            addView(Ui.button(this@PictureActivity, palette, getString(R.string.picture_choose), R.drawable.ic_picture).apply {
                tag = "picture_choose"
                setOnClickListener { choosePhoto() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        column.addView(choices)
        // Measurements for the test bench (debug builds), not a sentence: content, like a message's words.
        details = Ui.content(Ui.text(this, "", palette.muted)).apply {
            tag = "picture_details"
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        }
        column.addView(details)
        sizes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad() / 2, 0, 0) }
        looks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad() / 2, 0, 0) }
        column.addView(sizes)
        column.addView(looks)
        sendButton = Ui.button(this, palette, getString(R.string.send), R.drawable.ic_send, filled = true).apply {
            tag = "picture_send"
            setOnClickListener { send() }
        }
        column.addView(sendButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = pad()
        })
        show(ScrollView(this).apply { isFillViewport = true; addView(column) })
        renderChoices()
        showPictureControls(false)
    }

    /** Sizes, looks and Send appear once there is a photo to send. */
    private fun showPictureControls(shown: Boolean) {
        val v = if (shown) View.VISIBLE else View.GONE
        sizes.visibility = v
        looks.visibility = v
        sendButton.visibility = v
        if (!shown) { heavy.visibility = View.GONE; details.text = "" }
    }

    private fun renderChoices() {
        sizes.removeAllViews()
        listOf(PictureSize.SMALL to R.string.picture_small, PictureSize.MEDIUM to R.string.picture_medium, PictureSize.LARGE to R.string.picture_large)
            .forEach { (choice, label) ->
                sizes.addView(choiceButton(getString(label), choice == size, "size:${choice.name}") { size = choice; renderChoices(); prepare() })
            }
        looks.removeAllViews()
        Look.entries.forEach { choice ->
            looks.addView(choiceButton(getString(choice.label), choice == look, "look:${choice.name}") { look = choice; renderChoices(); prepare() })
        }
    }

    private fun choiceButton(label: String, chosen: Boolean, tag: String, onClick: () -> Unit): View =
        // Filled marks the choice; a tick would leave too little room and break words ("Moye-nne").
        Ui.button(this, palette, label, null, filled = chosen).apply {
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            this.tag = tag
            isSelected = chosen
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@PictureActivity, 4) }
        }

    @Deprecated("Framework-only app: no AndroidX activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return // Back from the camera or the picker: the choice stays as it was.
        when (requestCode) {
            REQUEST_PICK -> {
                val picked = data?.data ?: run { say(R.string.picture_unreadable); return }
                uri = picked
                load()
            }
            REQUEST_CAMERA -> {
                val file = CaptureProvider.fileIn(this)
                if (file.length() > 0) {
                    uri = Uri.fromFile(file)
                    load()
                } else {
                    // Some camera apps ignore the file and return only a small copy; enough for a radio picture.
                    @Suppress("DEPRECATION")
                    val small = data?.extras?.get("data") as? Bitmap
                    if (small != null) { uri = null; source = small; prepare() } else say(R.string.picture_no_photo)
                }
            }
        }
    }

    /** Reads the photo off the main thread, then prepares the picture. */
    private fun load() {
        val target = uri ?: return
        Ui.enable(sendButton, false)
        say(R.string.picture_working, bad = false)
        Thread {
            val bitmap = try {
                PictureMaker.load(contentResolver, target)
            } catch (e: Exception) {
                Log.w(TAG, "photo not readable", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "photo too large to read", e)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bitmap == null) { say(R.string.picture_unreadable); return@runOnUiThread }
                source = bitmap
                prepare()
            }
        }.start()
    }

    private fun prepare() {
        val from = source ?: return
        val mine = ++job
        val chosenSize = size
        val chosenLook = look
        Ui.enable(sendButton, false)
        say(R.string.picture_working, bad = false)
        Thread {
            val result = runCatching { PictureMaker.prepare(from, chosenSize, chosenLook == Look.GREY, chosenLook == Look.SKETCH) }
                .onFailure { Log.w(TAG, "picture not made", it) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || mine != job) return@runOnUiThread
                prepared = result
                showPrepared()
            }
        }.start()
    }

    private fun showPrepared() {
        val picture = prepared
        if (picture == null) { showPictureControls(false); say(R.string.picture_failed); return }
        showPictureControls(true)
        // The other phone's pixels, enlarged smoothly: what it will show.
        preview.setImageDrawable(BitmapDrawable(resources, picture.preview).apply { isFilterBitmap = true })
        val seconds = core.pictureSeconds(picture)
        info.text = getString(R.string.picture_time, ceil(seconds).toInt().coerceAtLeast(1))
        info.setTextColor(palette.text)
        heavy.visibility = if (seconds > HEAVY_SECONDS) View.VISIBLE else View.GONE
        details.text = "${picture.width}×${picture.height} px · ${picture.bytes.size} B + ${picture.sketch.size} B sketch · " +
            "q${picture.quality} · ${"%.1f".format(seconds)} s · ${core.radioShape().let { "SF${it.sf}/${it.bandwidthKhz.toInt()}" }}"
        Ui.enable(sendButton, true)
    }

    private fun send() {
        val picture = prepared ?: return
        if (core.sendPicture(chatId, picture)) finish() else say(R.string.picture_not_sent)
    }

    override fun onDestroy() {
        // The camera's photo is only a step: once this screen is done, nothing of it stays on the phone.
        if (isFinishing) CaptureProvider.fileIn(this).delete()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        uri?.let { outState.putParcelable(STATE_URI, it) }
        outState.putString(STATE_SIZE, size.name)
        outState.putString(STATE_LOOK, look.name)
        super.onSaveInstanceState(outState)
    }

    override fun render() = Unit

    companion object {
        const val EXTRA_CHAT_ID = "chat"
        private const val EXTRA_PHOTO = "photo"
        /** 10 % of the hour's allowance in EU 868 (360 s at a 10 % duty cycle). */
        const val HEAVY_SECONDS = 36.0
        private const val REQUEST_PICK = 7
        private const val REQUEST_CAMERA = 8
        private const val TAG = "PhonemeshPicture"
        private const val STATE_URI = "uri"
        private const val STATE_SIZE = "size"
        private const val STATE_LOOK = "look"

        /** [photo]: one already chosen (from this app only; the screen is not exported). */
        fun intent(context: Context, chatId: Long, photo: Uri? = null): Intent =
            Intent(context, PictureActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId).apply { if (photo != null) putExtra(EXTRA_PHOTO, photo) }
    }
}
