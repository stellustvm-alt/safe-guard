package com.spirit.focusguard

import android.content.Context

/** Saved state: your blocklist and any 24-hour pending requests. */
object Store {
    const val DELAY_MS = 24L * 60 * 60 * 1000   // 24 hours
    const val UNLOCK = "unlock"
    private const val REMOVE = "remove:"

    data class Pending(val time: Long, val action: String) {
        val readyAt get() = time + DELAY_MS
        fun isReady() = System.currentTimeMillis() >= readyAt
        val isUnlock get() = action == UNLOCK
        val domain get() = action.removePrefix(REMOVE)
        fun encode() = "$time|$action"
    }

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("focusguard", Context.MODE_PRIVATE)

    fun isActive(c: Context) = prefs(c).getBoolean("active", false)
    fun setActive(c: Context, v: Boolean) = prefs(c).edit().putBoolean("active", v).apply()

    fun sites(c: Context): Set<String> =
        prefs(c).getStringSet("sites", emptySet())!!.toSortedSet()

    private fun saveSites(c: Context, s: Set<String>) {
        prefs(c).edit().putStringSet("sites", HashSet(s)).apply()
        DnsVpnService.reload(c)
        if (isActive(c)) Policy.applyChrome(c)
    }

    /** Clean up "https://www.example.com/abc" -> "example.com". Returns null if invalid. */
    fun normalize(input: String): String? {
        var d = input.trim().lowercase()
        d = d.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
        d = d.removePrefix("www.").trimEnd('.')
        return if (Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(d)) d else null
    }

    /** Adding a site is instant (makes you safer). */
    fun addSite(c: Context, domain: String) = saveSites(c, sites(c) + domain)

    /** Before protection is switched on, you can still remove freely. */
    fun removeBeforeActivation(c: Context, domain: String) {
        if (!isActive(c)) saveSites(c, sites(c) - domain)
    }

    /** Removing a site or unlocking needs a 24-hour wait. */
    fun requestRemoval(c: Context, domain: String) = request(c, REMOVE + domain)
    fun requestUnlock(c: Context) = request(c, UNLOCK)

    private fun request(c: Context, action: String) {
        val list = pending(c)
        if (list.any { it.action == action }) return
        save(c, list + Pending(System.currentTimeMillis(), action))
    }

    fun pending(c: Context): List<Pending> =
        prefs(c).getStringSet("pending", emptySet())!!.mapNotNull {
            val i = it.indexOf('|')
            val t = it.substring(0, maxOf(i, 0)).toLongOrNull()
            if (i < 0 || t == null) null else Pending(t, it.substring(i + 1))
        }.sortedBy { it.time }

    private fun save(c: Context, list: List<Pending>) =
        prefs(c).edit().putStringSet("pending", list.map { it.encode() }.toHashSet()).apply()

    fun cancel(c: Context, p: Pending) = save(c, pending(c).filter { it.action != p.action })

    /** Carry out a request once its 24 hours are over. */
    fun apply(c: Context, p: Pending): Boolean {
        if (!p.isReady()) return false
        cancel(c, p)
        if (p.isUnlock) Policy.release(c) else saveSites(c, sites(c) - p.domain)
        return true
    }
}
