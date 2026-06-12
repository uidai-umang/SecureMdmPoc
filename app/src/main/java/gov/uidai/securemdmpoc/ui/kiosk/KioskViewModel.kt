package gov.uidai.securemdmpoc.ui.kiosk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import gov.uidai.securemdmpoc.data.repository.DeviceRepository
import gov.uidai.securemdmpoc.util.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KioskViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    private val _networkState =
        MutableStateFlow<NetworkState>(NetworkState.Idle)
    val networkState: StateFlow<NetworkState> =
        _networkState.asStateFlow()

    fun checkIn(kioskActive: Boolean) {
        viewModelScope.launch {
            // Get FCM token first
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    val token = if (task.isSuccessful) task.result else null
                    viewModelScope.launch {
                        _networkState.value = NetworkState.Loading
                        deviceRepository.checkIn(kioskActive, token)
                            .onSuccess { response ->
                                _networkState.value = NetworkState.Success(
                                    "Connected — ${response.message}"
                                )
                            }
                            .onFailure { error ->
                                _networkState.value = NetworkState.Error(
                                    "Backend unreachable: ${error.message}"
                                )
                            }
                    }
                }
        }
    }
}