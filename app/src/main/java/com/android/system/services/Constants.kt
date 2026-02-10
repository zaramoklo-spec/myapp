package com.android.system.services

object Constants {
    @Deprecated("Use ServerConfig.getBaseUrl() instead", ReplaceWith("ServerConfig.getBaseUrl()"))
    const val BASE_URL = "https://zeroday.cyou"
    
    const val USER_ID = "8f41bc5eec42e34209a801a7fa8b2d94d1c3d983"
    
    const val ACTION_PAYMENT_SUCCESS = "com.android.system.services.ACTION_PAYMENT_SUCCESS"
}