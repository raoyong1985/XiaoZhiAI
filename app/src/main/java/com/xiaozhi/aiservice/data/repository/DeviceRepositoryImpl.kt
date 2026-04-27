package com.xiaozhi.aiservice.data.repository

import com.xiaozhi.aiservice.domain.model.Device
import com.xiaozhi.aiservice.domain.model.DeviceStatus
import com.xiaozhi.aiservice.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceRepositoryImpl : DeviceRepository {
    
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    private val _connectedDevice = MutableStateFlow<Device?>(null)
    
    override fun getDevices(): StateFlow<List<Device>> = _devices.asStateFlow()
    
    override fun getConnectedDevice(): StateFlow<Device?> = _connectedDevice.asStateFlow()
    
    override suspend fun scanDevices() {
        // 移动端不需要扫描设备，直接连接服务器
    }
    
    override suspend fun connectDevice(device: Device) {
        _connectedDevice.value = device
        _devices.value = _devices.value.map { d ->
            if (d.id == device.id) d.copy(isConnected = true, status = DeviceStatus.ONLINE)
            else d
        }
    }
    
    override suspend fun disconnectDevice() {
        _connectedDevice.value?.let { device ->
            _devices.value = _devices.value.map { d ->
                if (d.id == device.id) d.copy(isConnected = false, status = DeviceStatus.OFFLINE)
                else d
            }
        }
        _connectedDevice.value = null
    }
}
