package com.spirit.focusguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager

/** Device Owner rules that stop the app being removed or bypassed. */
object Policy {
    private const val CHROME = "com.android.chrome"

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,      // no reset from Settings
        UserManager.DISALLOW_SAFE_BOOT,          // safe mode would skip the filter
        UserManager.DISALLOW_CONFIG_VPN,         // can't switch the filter VPN off
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS, // can't override DNS
        UserManager.DISALLOW_CONFIG_DATE_TIME,   // can't move the clock to skip 24h
        UserManager.DISALLOW_ADD_USER,           // no unfiltered second profile
    )

    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)
    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    fun activate(c: Context) {
        val d = dpm(c); val a = admin(c)
        d.setUninstallBlocked(a, c.packageName, true)
        RESTRICTIONS.forEach { d.addUserRestriction(a, it) }
        if (Build.VERSION.SDK_INT >= 30) {
            // blocks Force stop / Clear data for this app only
            d.setUserControlDisabledPackages(a, listOf(c.packageName))
        } else {
            d.addUserRestriction(a, UserManager.DISALLOW_APPS_CONTROL)
        }
        runCatching { d.setGlobalPrivateDnsModeOpportunistic(a) }
        d.setAlwaysOnVpnPackage(a, c.packageName, false)
        Store.setActive(c, true)
        applyChrome(c)
        c.startService(Intent(c, DnsVpnService::class.java))
    }

    /** Final step: turns off Developer options / USB debugging so adb can't be used to undo things. */
    fun lockDeveloperOptions(c: Context) =
        dpm(c).addUserRestriction(admin(c), UserManager.DISALLOW_DEBUGGING_FEATURES)

    fun devOptionsLocked(c: Context) =
        c.getSystemService(UserManager::class.java)
            .hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES)

    /** Chrome managed settings: SafeSearch, adult-site filter, no incognito, no private DNS-over-HTTPS. */
    fun applyChrome(c: Context) {
        val b = Bundle().apply {
            putString("DnsOverHttpsMode", "off")
            putInt("IncognitoModeAvailability", 1)
            putInt("SafeSitesFilterBehavior", 1)
            putBoolean("ForceGoogleSafeSearch", true)
            putInt("ForceYouTubeRestrict", 2)
            putStringArray("URLBlocklist", Store.sites(c).toTypedArray())
        }
        runCatching { dpm(c).setApplicationRestrictions(admin(c), CHROME, b) }
    }

    /** Only called after a 24-hour unlock request has matured. */
    fun release(c: Context) {
        val d = dpm(c); val a = admin(c)
        (RESTRICTIONS + UserManager.DISALLOW_DEBUGGING_FEATURES + UserManager.DISALLOW_APPS_CONTROL)
            .forEach { runCatching { d.clearUserRestriction(a, it) } }
        if (Build.VERSION.SDK_INT >= 30) runCatching { d.setUserControlDisabledPackages(a, emptyList()) }
        runCatching { d.setAlwaysOnVpnPackage(a, null, false) }
        runCatching { d.setApplicationRestrictions(a, CHROME, Bundle()) }
        d.setUninstallBlocked(a, c.packageName, false)
        Store.setActive(c, false)
        c.stopService(Intent(c, DnsVpnService::class.java))
        d.clearDeviceOwnerApp(c.packageName)
    }
}
