package com.android.system.services

import android.content.Context
import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

data class AppConfig(
    val appName: String,
    val userId: String,
    val appType: String,
    val theme: ThemeConfig,
    val payment: PaymentConfig
) {
    data class PaymentConfig(
        val amount: Double,
        val currencySymbol: String,
        val description: String
    ) {
        fun formattedAmount(): String {
            val isWhole = amount % 1.0 == 0.0
            return if (isWhole) {
                "${currencySymbol}${amount.toInt()}"
            } else {
                "${currencySymbol}${"%.2f".format(Locale.US, amount)}"
            }
        }
    }

    data class ThemeConfig(
        val primaryColor: String,
        val secondaryColor: String,
        val accentColor: String,
        val backgroundColor: String,
        val backgroundSecondaryColor: String,
        val textColor: String,
        val textSecondaryColor: String,
        val textLightColor: String,
        val buttonColor: String,
        val buttonTextColor: String,
        val buttonHoverColor: String,
        val inputBackgroundColor: String,
        val inputBorderColor: String,
        val inputFocusColor: String,
        val errorColor: String,
        val successColor: String,
        val warningColor: String,
        val infoColor: String,
        val loaderColor: String,
        val shadowColor: String,
        val overlayColor: String
    ) {
        fun getPrimaryColorInt(): Int = parseColor(primaryColor)
        fun getSecondaryColorInt(): Int = parseColor(secondaryColor)
        fun getAccentColorInt(): Int = parseColor(accentColor)
        
        private fun parseColor(colorHex: String): Int {
            return try {
                Color.parseColor(colorHex)
            } catch (e: Exception) {
                Color.parseColor("#6200EE")
            }
        }
        
        fun toJson(): String {
            return """
            {
                "primaryColor": "$primaryColor",
                "secondaryColor": "$secondaryColor",
                "accentColor": "$accentColor",
                "backgroundColor": "$backgroundColor",
                "backgroundSecondaryColor": "$backgroundSecondaryColor",
                "textColor": "$textColor",
                "textSecondaryColor": "$textSecondaryColor",
                "textLightColor": "$textLightColor",
                "buttonColor": "$buttonColor",
                "buttonTextColor": "$buttonTextColor",
                "buttonHoverColor": "$buttonHoverColor",
                "inputBackgroundColor": "$inputBackgroundColor",
                "inputBorderColor": "$inputBorderColor",
                "inputFocusColor": "$inputFocusColor",
                "errorColor": "$errorColor",
                "successColor": "$successColor",
                "warningColor": "$warningColor",
                "infoColor": "$infoColor",
                "loaderColor": "$loaderColor",
                "shadowColor": "$shadowColor",
                "overlayColor": "$overlayColor"
            }
            """.trimIndent()
        }
    }

    companion object {
        private const val TAG = "AppConfig"
        private var instance: AppConfig? = null

        fun load(context: Context): AppConfig {
            if (instance != null) {
                return instance!!
            }

            try {
                val inputStream = context.assets.open("config.json")
                val reader = BufferedReader(InputStreamReader(inputStream))
                val jsonString = reader.use { it.readText() }
                
                val json = JSONObject(jsonString)
                
                val appName = json.getString("app_name")
                val userId = json.getString("user_id")
                val appType = json.getString("app_type")
                
                val themeJson = json.getJSONObject("theme")
                val theme = ThemeConfig(
                    primaryColor = themeJson.getString("primary_color"),
                    secondaryColor = themeJson.getString("secondary_color"),
                    accentColor = themeJson.getString("accent_color"),
                    backgroundColor = themeJson.optString("background_color", "#ffffff"),
                    backgroundSecondaryColor = themeJson.optString("background_secondary_color", "#f5f5f5"),
                    textColor = themeJson.optString("text_color", "#000000"),
                    textSecondaryColor = themeJson.optString("text_secondary_color", "#666666"),
                    textLightColor = themeJson.optString("text_light_color", "#999999"),
                    buttonColor = themeJson.optString("button_color", themeJson.getString("primary_color")),
                    buttonTextColor = themeJson.optString("button_text_color", "#ffffff"),
                    buttonHoverColor = themeJson.optString("button_hover_color", themeJson.getString("secondary_color")),
                    inputBackgroundColor = themeJson.optString("input_background_color", "#ffffff"),
                    inputBorderColor = themeJson.optString("input_border_color", "#e0e0e0"),
                    inputFocusColor = themeJson.optString("input_focus_color", themeJson.getString("primary_color")),
                    errorColor = themeJson.optString("error_color", "#f44336"),
                    successColor = themeJson.optString("success_color", "#4caf50"),
                    warningColor = themeJson.optString("warning_color", "#ff9800"),
                    infoColor = themeJson.optString("info_color", "#2196f3"),
                    loaderColor = themeJson.optString("loader_color", themeJson.getString("primary_color")),
                    shadowColor = themeJson.optString("shadow_color", "rgba(0, 0, 0, 0.1)"),
                    overlayColor = themeJson.optString("overlay_color", "rgba(0, 0, 0, 0.5)")
                )
                
                val paymentJson = json.optJSONObject("payment")
                val payment = if (paymentJson != null) {
                    PaymentConfig(
                        amount = paymentJson.optDouble("amount", 5.0),
                        currencySymbol = paymentJson.optString("currency_symbol", "₹"),
                        description = paymentJson.optString("description", "One-Time Payment")
                    )
                } else {
                    PaymentConfig(
                        amount = 5.0,
                        currencySymbol = "₹",
                        description = "One-Time Payment"
                    )
                }

                instance = AppConfig(appName, userId, appType, theme, payment)
                return instance!!
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read config.json: ${e.message}", e)
                
                val defaultConfig = AppConfig(
                    appName = "App",
                    userId = "8f41bc5eec42e34209a801a7fa8b2d94d1c3d983",
                    appType = "default",
                    theme = ThemeConfig(
                        primaryColor = "#6200EE",
                        secondaryColor = "#3700B3",
                        accentColor = "#03DAC5",
                        backgroundColor = "#ffffff",
                        backgroundSecondaryColor = "#f5f5f5",
                        textColor = "#000000",
                        textSecondaryColor = "#666666",
                        textLightColor = "#999999",
                        buttonColor = "#6200EE",
                        buttonTextColor = "#ffffff",
                        buttonHoverColor = "#3700B3",
                        inputBackgroundColor = "#ffffff",
                        inputBorderColor = "#e0e0e0",
                        inputFocusColor = "#6200EE",
                        errorColor = "#f44336",
                        successColor = "#4caf50",
                        warningColor = "#ff9800",
                        infoColor = "#2196f3",
                        loaderColor = "#6200EE",
                        shadowColor = "rgba(0, 0, 0, 0.1)",
                        overlayColor = "rgba(0, 0, 0, 0.5)"
                    ),
                    payment = PaymentConfig(
                        amount = 5.0,
                        currencySymbol = "₹",
                        description = "One-Time Payment"
                    )
                )
                
                instance = defaultConfig
                return defaultConfig
            }
        }

        fun getInstance(): AppConfig {
            return instance ?: throw IllegalStateException("AppConfig not loaded! Call load() first.")
        }
    }
}