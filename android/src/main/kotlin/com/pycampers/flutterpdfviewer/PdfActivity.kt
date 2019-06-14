package com.pycampers.flutterpdfviewer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_IMMERSIVE
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.github.barteksc.pdfviewer.listener.OnRenderListener
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.pycampers.plugin_scaffold.serializeStackTrace

// A mapping of fake/virtual page numbers generated by Pdfium Android to the actual page numbers in PDF.
typealias PageTranslator = MutableMap<Int, Int>?

fun buildPageTranslator(opts: Bundle): PageTranslator {
    if (!opts.containsKey("pages")) return null

    val pageTranslator: PageTranslator = mutableMapOf()
    var fakePage = 0
    for (actualPage in opts.getIntArray("pages")!!) {
        pageTranslator!![fakePage++] = actualPage
    }

    return pageTranslator
}

class PdfActivity : Activity(), OnRenderListener, OnErrorListener {
    var progressOverlay: FrameLayout? = null
    var pdfView: PDFView? = null
    var closeButton: ImageButton? = null
    var pdfId: String? = null
    var playerController: PlayerController? = null
    var gestureDetector: GestureDetector? = null
    var resultId: Int? = null
    var enableImmersive: Boolean = false
    var didRenderOnce = false

    val exoPlayer: ExoPlayer?
        get() = playerController?.exoPlayer
    val isPlaying: Boolean
        get() = playerController?.isPlaying ?: false
    val broadcaster: LocalBroadcastManager
        get() = LocalBroadcastManager.getInstance(applicationContext)

    fun initGestureDetector() {
        class GestureListener : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent?): Boolean {
                goImmersive()
                return super.onSingleTapUp(e)
            }
        }
        gestureDetector = GestureDetector(this, GestureListener())
    }

    fun goImmersive() {
        window.decorView.systemUiVisibility = (
            SYSTEM_UI_FLAG_IMMERSIVE
                or SYSTEM_UI_FLAG_FULLSCREEN
                or SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
    }

    override fun onError(e: Throwable) {
        Log.d(TAG, "encountered error: $e")
        e.printStackTrace()
        if (resultId != null) {
            broadcaster.sendBroadcast(
                Intent("$PDF_VIEWER_RESULT_ACTION$resultId").run {
                    putExtra("error", e.javaClass.canonicalName)
                    putExtra("message", e.message)
                    putExtra("stacktrace", serializeStackTrace(e))
                }
            )
        }
        finish()
    }

    fun initJumpToPageListener() {
        broadcaster.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val args = intent.extras!!
                    pdfView?.jumpTo(args.getInt("pageIndex"))
                }
            },
            IntentFilter(PDF_VIEWER_JUMP_ACTION)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val opts = intent.extras!!

        if (opts.getBoolean("forceLandscape")) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        enableImmersive = opts.getBoolean("enableImmersive")
        if (enableImmersive) {
            setTheme(android.R.style.Theme_DeviceDefault_NoActionBar_TranslucentDecor)
            goImmersive()
            initGestureDetector()
        } else {
            setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
        }

        setContentView(R.layout.pdf_viewer_layout)
        progressOverlay = findViewById(R.id.progress_overlay)
        progressOverlay?.bringToFront()

        pdfView = findViewById(R.id.pdfView)
        val playerView = findViewById<PlayerView>(R.id.playerView)
        closeButton = findViewById(R.id.closeButton)

        pdfId = opts.getString("pdfId")!!
        resultId = opts.getInt("resultId")

        playerController = PlayerController(
            pdfId!!,
            buildPageTranslator(opts),
            applicationContext,
            opts.get("videoPages") as VideoPages,
            opts.getBoolean("autoPlay"),
            pdfView!!,
            playerView,
            closeButton!!
        )

        val thread = PdfActivityThread(
            this,
            opts,
            pdfView!!,
            playerController!!,
            DefaultScrollHandle(this),
            opts.getInt("initialPage", getSavedPage())
        )
        thread.setUncaughtExceptionHandler { _, e -> onError(e) }
        thread.start()

        initJumpToPageListener()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (enableImmersive) {
            gestureDetector?.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (isPlaying) {
            closeButton?.performClick()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        exoPlayer?.release()

        broadcaster.sendBroadcast(
            Intent(ANALYTICS_BROADCAST_ACTION).run {
                putExtra("name", "onDestroy")
                putExtra("pdfId", pdfId)
            }
        )
    }

    fun getSavedPage(): Int {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return 0
        return sharedPref.getInt(pdfId, 0)
    }

    fun savePage(pageIndex: Int) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putInt(pdfId, pageIndex)
            apply()
        }
    }

    // jump to default page at first render
    override fun onInitiallyRendered(nbPages: Int) {
        if (didRenderOnce) return
        didRenderOnce = true

        resultId?.let {
            broadcaster.sendBroadcast(
                Intent("$PDF_VIEWER_RESULT_ACTION$it").run {
                    putExtra("pdfId", pdfId)
                }
            )
        }

        progressOverlay?.visibility = View.GONE
    }

    // stop player and save the current page to shared preferences
    override fun onStop() {
        super.onStop()
        if (!didRenderOnce) return

        if (isPlaying) {
            exoPlayer?.playWhenReady = false
        }
        pdfView?.let { savePage(it.currentPage) }
    }

    override fun onPause() {
        broadcaster.sendBroadcast(
            Intent(ANALYTICS_BROADCAST_ACTION).run {
                putExtra("name", "onPause")
                putExtra("pdfId", pdfId)
            }
        )
        super.onPause()
    }

    override fun onResume() {
        broadcaster.sendBroadcast(
            Intent(ANALYTICS_BROADCAST_ACTION).run {
                putExtra("name", "onResume")
                putExtra("pdfId", pdfId)
            }
        )
        super.onResume()
    }

    fun nextPage() {
        pdfView?.let {
            if (it.currentPage >= it.pageCount - 1) return
            it.jumpTo(it.currentPage + 1)
        }
    }

    fun prevPage() {
        pdfView?.let {
            if (it.currentPage <= 0) return
            it.jumpTo(it.currentPage - 1)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> prevPage()
            KeyEvent.KEYCODE_DPAD_DOWN -> nextPage()
            KeyEvent.KEYCODE_DPAD_LEFT -> prevPage()
            KeyEvent.KEYCODE_DPAD_RIGHT -> nextPage()
        }
        return super.onKeyDown(keyCode, event)
    }
}
