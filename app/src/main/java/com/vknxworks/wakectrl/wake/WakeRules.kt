package com.vknxworks.wakectrl.wake

/** AppWake's per-app choice, worked out without Android so it can be unit-tested. */
object AppWakeRule {
    /**
     * Whether [pkg] on screen keeps the screen on: AppWake is on, the app is chosen, and, for apps set to
     * "Only while charging", the phone is charging.
     */
    fun keepsOn(pkg: String?, enabled: Boolean, packages: Set<String>, chargingOnly: Set<String>, charging: Boolean): Boolean =
        enabled && pkg != null && pkg in packages && (pkg !in chargingOnly || charging)
}
