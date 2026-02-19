package dev.anilbeesetti.nextplayer.feature.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class YifiWebViewActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (startUrl.isBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        webView = WebView(this)
        setContentView(webView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = WEB_USER_AGENT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.setDownloadListener { url, userAgent, _, _, _ ->
            val cookies = cookieManager.getCookie(url).orEmpty()
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_CAPTURED_DOWNLOAD_URL, url)
                    putExtra(EXTRA_CAPTURED_COOKIES, cookies)
                    putExtra(EXTRA_CAPTURED_USER_AGENT, userAgent)
                },
            )
            finish()
        }
        webView.loadUrl(startUrl)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_CAPTURED_DOWNLOAD_URL = "extra_captured_download_url"
        const val EXTRA_CAPTURED_COOKIES = "extra_captured_cookies"
        const val EXTRA_CAPTURED_USER_AGENT = "extra_captured_user_agent"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; NextPlayer) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        fun createIntent(context: Context, url: String): Intent {
            return Intent(context, YifiWebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
        }
    }
}
