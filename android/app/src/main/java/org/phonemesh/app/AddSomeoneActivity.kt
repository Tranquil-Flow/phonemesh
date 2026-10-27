package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * "Ajouter quelqu'un" (plan step 11; how-to pictures add_qr, add_link): this phone's code on top, then "Scanner un
 * code" and "Envoyer mon lien". Both people stay on this screen and take turns: one scans, then the other.
 * Requests from people who opened this phone's link show here too, with Accept and Ignore.
 */
class AddSomeoneActivity : ScreenActivity() {
    private lateinit var code: ImageView
    private lateinit var requests: LinearLayout
    private var shownText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.add_title)))
        column.addView(noteLine())
        requests = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "requests" }
        column.addView(requests)
        code = ImageView(this).apply {
            tag = "my_code"
            contentDescription = getString(R.string.add_my_code)
            setBackgroundColor(Color.WHITE)
        }
        val size = Ui.dp(this, 260)
        column.addView(code, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = pad() })
        column.addView(Ui.content(Ui.text(this, "", palette.muted).apply { tag = "my_name"; gravity = Gravity.CENTER }),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(Ui.button(this, palette, getString(R.string.add_scan), R.drawable.ic_camera, filled = true).apply {
            tag = "add_scan"
            setOnClickListener { startActivity(Intent(this@AddSomeoneActivity, ScanActivity::class.java)) }
        }, full())
        column.addView(Ui.button(this, palette, getString(R.string.add_send_link), R.drawable.ic_send).apply {
            tag = "add_link"
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, core.accountsFlow.link()), null))
            }
        }, full())
        show(ScrollView(this).apply { addView(column) })
        render()
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = pad() }

    override fun render() {
        if (!::code.isInitialized) return
        val text = core.accountsFlow.showCode()
        if (shownText != text) {
            code.setImageBitmap(qr(text, Ui.dp(this, 260)))
            shownText = text
        }
        (window.decorView.findViewWithTag<View>("my_name") as? TextView)?.text =
            getString(R.string.add_my_code_of, core.account()?.first.orEmpty())
        requests.removeAllViews()
        core.accountsFlow.requests().forEach { request -> requests.addView(requestCard(request)) }
    }

    /** "<name> veut vous parler", with ✓ Accepter and ✕ Ignorer (picture add_accept). */
    private fun requestCard(request: Accounts.Request): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "request"
        val p = pad()
        setPadding(p, p, p, p)
        background = Ui.outline(this@AddSomeoneActivity, palette.surface, palette.outline)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = p / 2 }
        val head = LinearLayout(this@AddSomeoneActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Looks.avatar(this@AddSomeoneActivity, palette, ChatKind.PERSON, 40, Face.of(request.hello.profile)),
            LinearLayout.LayoutParams(Ui.dp(this@AddSomeoneActivity, 40), Ui.dp(this@AddSomeoneActivity, 40)))
        head.addView(Ui.text(this@AddSomeoneActivity, getString(R.string.add_wants_to_talk, request.name), palette.text, 20f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = p })
        addView(head)
        val buttons = LinearLayout(this@AddSomeoneActivity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Ui.button(this@AddSomeoneActivity, palette, getString(R.string.add_accept), R.drawable.ic_check, filled = true).apply {
            tag = "request_accept"
            setOnClickListener {
                core.acceptRequest(request)?.let { SafetyActivity.open(this@AddSomeoneActivity, it.account) }
                render()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Ui.button(this@AddSomeoneActivity, palette, getString(R.string.add_ignore), R.drawable.ic_close).apply {
            tag = "request_ignore"
            setOnClickListener { core.ignoreRequest(request); render() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = p / 2 })
        addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = p / 2 })
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, AddSomeoneActivity::class.java))

        /** [text] as a black-on-white QR code, error correction M, with the standard quiet zone. */
        fun qr(text: String, size: Int): Bitmap {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        }
    }
}
