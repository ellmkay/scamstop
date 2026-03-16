package com.scamkill.app.viewmodel

import android.app.Application
import android.app.role.RoleManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scamkill.app.ScamKillApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val backendConnected: Boolean = false,
    val callScreeningActive: Boolean = false,
    val smsScreeningEnabled: Boolean = true,
    val isDefaultSmsApp: Boolean = false,
    val callsScreenedToday: Int = 0,
    val smsAnalyzedToday: Int = 0,
    val smsBlockedToday: Int = 0,
    val isLoading: Boolean = true,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ScamKillApp
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)

            val health = try {
                app.api.healthCheck()
            } catch (e: Exception) {
                null
            }

            val hasCallRole = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = app.getSystemService(RoleManager::class.java)
                rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            } else {
                false
            }

            val isDefault = checkIsDefaultSmsApp()
            Log.i("ScamKill", "refresh: isDefaultSmsApp=$isDefault callRole=$hasCallRole backend=${health?.ok}")

            _state.value = HomeUiState(
                backendConnected = health?.ok == true,
                callScreeningActive = hasCallRole && app.preferences.callScreeningEnabled,
                smsScreeningEnabled = app.preferences.smsScreeningEnabled,
                isDefaultSmsApp = isDefault,
                callsScreenedToday = app.preferences.callsScreenedToday,
                smsAnalyzedToday = app.preferences.smsAnalyzedToday,
                smsBlockedToday = app.preferences.smsBlockedToday,
                isLoading = false,
            )
        }
    }

    private fun checkIsDefaultSmsApp(): Boolean {
        // Check via RoleManager (most reliable on Q+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = app.getSystemService(RoleManager::class.java)
            if (rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                return true
            }
        }
        // Fallback: check default SMS package
        val defaultPkg = Telephony.Sms.getDefaultSmsPackage(app)
        val ourPkg = app.packageName
        Log.i("ScamKill", "defaultSmsPkg=$defaultPkg ourPkg=$ourPkg")
        return defaultPkg == ourPkg
    }
}
