package com.spirit.focusguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 30_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 60) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun render() {
        root.removeAllViews()
        title("FocusGuard")

        if (!Policy.isOwner(this)) {
            text("Not set up yet. Connect the phone to your PC and run:\n\n" +
                 "adb shell dpm set-device-owner com.spirit.focusguard/.AdminReceiver\n\n" +
                 "See the README for the full steps.", mono = true)
            return
        }

        if (!Store.isActive(this)) {
            text("Device Owner OK. Add the sites you want blocked, then activate.\n" +
                 "Adult sites are already blocked by the family filter.")
            addSiteRow()
            button("ACTIVATE PROTECTION") {
                confirm("Once active, uninstalling or removing sites needs a 24-hour wait. Continue?") {
                    val prep = VpnService.prepare(this)
                    if (prep != null) startActivityForResult(prep, 1) else onActivityResult(1, RESULT_OK, null)
                }
            }
            siteList(editable = false)
            return
        }

        text("🛡️  Protection is ON", bold = true, color = Color.rgb(0, 120, 60))

        if (!Policy.devOptionsLocked(this)) {
            text("Final step (after you've tested everything for a day): lock Developer options so USB/adb can't be used to bypass.")
            button("LOCK DEVELOPER OPTIONS") {
                confirm("This turns off USB debugging permanently until you unlock FocusGuard (24h). Continue?") {
                    Policy.lockDeveloperOptions(this); render()
                }
            }
        }

        section("Blocked sites")
        addSiteRow()
        siteList(editable = true)

        val pending = Store.pending(this)
        if (pending.isNotEmpty()) {
            section("Waiting requests (24-hour delay)")
            pending.forEach { p ->
                val label = if (p.isUnlock) "Remove protection & allow uninstall" else "Unblock ${p.domain}"
                text(label, bold = true)
                if (p.isReady()) {
                    text("Ready. You've waited 24 hours — still want this?")
                    button("YES, DO IT NOW") {
                        Store.apply(this, p)
                        if (p.isUnlock) toast("Protection removed. You can uninstall FocusGuard from Settings → Apps.")
                        render()
                    }
                } else {
                    val left = p.readyAt - System.currentTimeMillis()
                    text("Available in ${left / 3_600_000}h ${(left / 60_000) % 60}m")
                }
                button("CANCEL REQUEST") { Store.cancel(this, p); render() }
            }
        }

        section("Uninstall")
        if (pending.none { it.isUnlock }) {
            button("REQUEST UNINSTALL (24h wait)") {
                confirm("The urge usually passes long before 24 hours. Start the timer anyway?") {
                    Store.requestUnlock(this); render()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == RESULT_OK) {
            try { Policy.activate(this); toast("Protection active") }
            catch (e: Exception) { toast("Activation failed: ${e.message}") }
            render()
        }
    }

    // ---------- small UI helpers ----------
    private fun addSiteRow() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply { hint = "example.com"; setSingleLine() }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "BLOCK"
            setOnClickListener {
                val d = Store.normalize(input.text.toString())
                if (d == null) toast("Enter a valid domain, e.g. example.com")
                else { Store.addSite(this@MainActivity, d); render() }
            }
        })
        root.addView(row)
    }

    private fun siteList(editable: Boolean) {
        val sites = Store.sites(this)
        if (sites.isEmpty()) text("(no custom sites yet)")
        val waiting = Store.pending(this).map { it.domain }.toSet()
        sites.forEach { d ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply { text = "• $d"; textSize = 16f },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (editable && d !in waiting) row.addView(Button(this).apply {
                text = "UNBLOCK (24h)"; textSize = 11f
                setOnClickListener { Store.requestRemoval(this@MainActivity, d); render() }
            })
            if (!editable) row.addView(Button(this).apply {   // before activation, removal is free
                text = "X"
                setOnClickListener { Store.removeBeforeActivation(this@MainActivity, d); render() }
            })
            root.addView(row)
        }
    }

    private fun title(s: String) = text(s, bold = true, size = 26f)
    private fun section(s: String) { root.addView(View(this), LinearLayout.LayoutParams(-1, 50)); text(s, bold = true, size = 19f) }
    private fun text(s: String, bold: Boolean = false, size: Float = 15f, color: Int = Color.DKGRAY, mono: Boolean = false) {
        root.addView(TextView(this).apply {
            text = s; textSize = size; setTextColor(color); setPadding(0, 12, 0, 12)
            typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
        })
    }
    private fun button(label: String, onClick: () -> Unit) =
        root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun confirm(msg: String, yes: () -> Unit) =
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> yes() }.setNegativeButton("No", null).show()
}
