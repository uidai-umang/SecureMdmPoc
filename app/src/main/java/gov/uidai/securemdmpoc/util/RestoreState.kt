package gov.uidai.securemdmpoc.util

sealed class RestoreState {
    object Idle : RestoreState()
    object Loading : RestoreState()
    object Success : RestoreState()
    object WrongPin : RestoreState()
    object TooMany : RestoreState()
    data class Error(val message: String) : RestoreState()
}