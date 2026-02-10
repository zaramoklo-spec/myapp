package com.android.system.services.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

object SimInfoHelper {
    private const val TAG = "SimInfoHelper"

    fun getSimInfo(context: Context): JSONArray {
        val simArray = JSONArray()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) return simArray
        
        val hasReadPhoneNumbers: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            val sims = subManager.activeSubscriptionInfoList

            if (!sims.isNullOrEmpty()) {
                sims.forEach { info ->
                    var phoneNumber = info.number ?: ""
                    
                    if (phoneNumber.isBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && hasReadPhoneNumbers) {
                        try {
                            val tm = telephonyManager.createForSubscriptionId(info.subscriptionId)
                            val line1Number = tm.line1Number
                            if (!line1Number.isNullOrBlank()) {
                                phoneNumber = line1Number
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get line1Number for SIM ${info.simSlotIndex}: ${e.message}")
                        }
                    }
                    
                    val sim = JSONObject().apply {
                        put("sim_slot", info.simSlotIndex)
                        put("subscription_id", info.subscriptionId)
                        put("carrier_name", info.carrierName?.toString() ?: "")
                        put("display_name", info.displayName?.toString() ?: "")
                        put("phone_number", phoneNumber)
                        put("country_iso", info.countryIso ?: "")
                        put("mcc", info.mccString ?: "")
                        put("mnc", info.mncString ?: "")
                        put("is_network_roaming", info.dataRoaming == SubscriptionManager.DATA_ROAMING_ENABLE)
                        put("icon_tint", info.iconTint)
                        put("card_id", info.cardId)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put("carrier_id", info.carrierId)
                            put("is_embedded", info.isEmbedded)
                            put("is_opportunistic", info.isOpportunistic)
                            put("icc_id", info.iccId ?: "")
                            val groupUuid = info.groupUuid
                            put("group_uuid", groupUuid?.toString() ?: "")
                        } else {
                            put("carrier_id", -1)
                            put("is_embedded", false)
                            put("is_opportunistic", false)
                            put("icc_id", "")
                            put("group_uuid", "")
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try {
                                put("port_index", info.portIndex)
                            } catch (e: Exception) {
                                put("port_index", -1)
                            }
                        } else {
                            put("port_index", -1)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            try {
                                val tm = telephonyManager.createForSubscriptionId(info.subscriptionId)

                                put("network_type", getNetworkTypeName(tm.dataNetworkType))
                                put("network_operator_name", tm.networkOperatorName ?: "")
                                put("network_operator", tm.networkOperator ?: "")
                                put("sim_operator_name", tm.simOperatorName ?: "")
                                put("sim_operator", tm.simOperator ?: "")
                                put("sim_state", getSimStateName(tm.simState))
                                put("phone_type", getPhoneTypeName(tm.phoneType))

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    try {
                                        put("imei", tm.imei ?: "")
                                        put("meid", tm.meid ?: "")
                                    } catch (e: Exception) {
                                        put("imei", "")
                                        put("meid", "")
                                    }
                                } else {
                                    put("imei", "")
                                    put("meid", "")
                                }

                                put("data_enabled", tm.isDataEnabled)
                                put("data_roaming_enabled", tm.isDataRoamingEnabled)
                                put("voice_capable", tm.isVoiceCapable)
                                put("sms_capable", tm.isSmsCapable)
                                put("has_icc_card", tm.hasIccCard())

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    try {
                                        put("device_software_version", tm.deviceSoftwareVersion ?: "")
                                        put("visual_voicemail_package_name", tm.visualVoicemailPackageName ?: "")
                                    } catch (e: Exception) {
                                        put("device_software_version", "")
                                        put("visual_voicemail_package_name", "")
                                    }
                                } else {
                                    put("device_software_version", "")
                                    put("visual_voicemail_package_name", "")
                                }

                                put("network_country_iso", tm.networkCountryIso ?: "")
                                put("sim_country_iso", tm.simCountryIso ?: "")

                            } catch (e: Exception) {
                                Log.e(TAG, "Error reading TelephonyManager for SIM ${info.simSlotIndex}: ${e.message}")
                            }
                        }
                    }
                    simArray.put(sim)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SIM Info error: ${e.message}", e)
        }
        return simArray
    }

    private fun getNetworkTypeName(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA (2G)"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO Rev.0 (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO Rev.A (3G)"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT (2G)"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA (3G)"
            TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN (2G)"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO Rev.B (3G)"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD (3G)"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+ (3G)"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM (2G)"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA (3G)"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                networkType == TelephonyManager.NETWORK_TYPE_NR) {
                "5G NR"
            } else {
                "Unknown"
            }
        }
    }

    private fun getSimStateName(state: Int): String {
        return when (state) {
            TelephonyManager.SIM_STATE_ABSENT -> "Absent"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network Locked"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN Required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK Required"
            TelephonyManager.SIM_STATE_READY -> "Ready"
            TelephonyManager.SIM_STATE_NOT_READY -> "Not Ready"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "Permanently Disabled"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card IO Error"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "Card Restricted"
            else -> "Unknown"
        }
    }

    private fun getPhoneTypeName(phoneType: Int): String {
        return when (phoneType) {
            TelephonyManager.PHONE_TYPE_NONE -> "None"
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            else -> "Unknown"
        }
    }
}