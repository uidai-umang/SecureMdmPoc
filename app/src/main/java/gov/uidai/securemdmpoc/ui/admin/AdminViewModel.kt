package gov.uidai.securemdmpoc.ui.admin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gov.uidai.securemdmpoc.manager.LockdownManager
import gov.uidai.securemdmpoc.util.RestoreState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

class AdminViewModel(private val lockdownManager: LockdownManager) : ViewModel() {

    // SHA256 hash of admin PIN
    // Default dev PIN: 123456
    private val ADMIN_PIN_HASH =
        "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92"

    private val _restoreState =
        MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private var failCount = 0

    fun verifyAndRestore(pin: String, context: Context) {
        if (pin.isBlank()) return

        viewModelScope.launch {
            _restoreState.value = RestoreState.Loading

            if (sha256(pin) == ADMIN_PIN_HASH) {
                failCount = 0
                lockdownManager.restoreDeviceToNormal()
                _restoreState.value = RestoreState.Success
            } else {
                failCount++
                _restoreState.value = if (failCount >= 3)
                    RestoreState.TooMany
                else
                    RestoreState.WrongPin
            }
        }
    }

    fun resetState() {
        _restoreState.value = RestoreState.Idle
        failCount = 0
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}