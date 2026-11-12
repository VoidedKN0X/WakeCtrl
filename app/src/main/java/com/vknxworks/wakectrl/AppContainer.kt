package com.vknxworks.wakectrl

import android.content.Context
import com.vknxworks.wakectrl.data.lock.LockVault
import com.vknxworks.wakectrl.data.settings.SettingsRepository
import com.vknxworks.wakectrl.ui.lock.AppLock
import com.vknxworks.wakectrl.wake.ActivityLog
import com.vknxworks.wakectrl.wake.WakeService
import com.vknxworks.wakectrl.wake.WakeState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)

    /** Which tab to open (the notification opens the Wake tab). */
    val openTab = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "wakectrl")

    init {
        ActivityLog.init(context)
        AppLock.onLocked = { onLocked() }
    }

    /** Applies changed settings to the running service (or starts it when an automation was switched on). */
    fun refreshWake() {
        if (WakeState.status.value.running || settings.settings.value.anyAutomation) {
            WakeService.send(context, WakeService.ACTION_REFRESH)
        }
    }

    suspend fun enableLock(pin: String) {
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
    }

    fun disableLock() {
        vault.disable()
    }

    /**
     * "Forgot PIN?": the PIN can't be recovered, and skipping it would make the lock pointless, so every
     * setting, automation and the activity log are deleted and the lock turned off.
     */
    suspend fun forgetEverything() {
        WakeService.send(context, WakeService.ACTION_OFF)
        settings.clear()
        ActivityLog.clear()
        vault.disable()
        AppLock.markUnlocked()
        context.stopService(android.content.Intent(context, WakeService::class.java))
    }

    fun onLocked() {
        vault.close()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }
}

val Context.container: AppContainer
    get() = (applicationContext as WakeCtrlApp).container
