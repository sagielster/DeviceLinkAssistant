package com.example.devicelinkassistant.ble

data class FoundBleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val bondState: Int
)
