# FocusGuard – porn & site blocker with a 24-hour lock

## What it does
- Blocks adult sites on **every app and browser** (all lookups go through Cloudflare for Families, 1.1.1.3).
- Blocks **your own list** of sites. Adding a site works instantly. Unblocking one takes **24 hours**.
- **Uninstalling takes 24 hours.** Tap "Request uninstall", wait 24h, confirm, and only then can it be removed.
- Blocks the usual workarounds: Safe Mode, factory reset from Settings, switching the VPN/DNS off,
  changing the clock to skip the timer, Force stop / Clear data, extra user profiles, and Chrome
  incognito / private DNS. Google SafeSearch and YouTube Restricted Mode are forced on in Chrome.

## 1. Get the APK (pick one)
**A. GitHub (no install needed):** create a free GitHub account → New repository → upload this whole
folder (including the hidden `.github` folder) → open the **Actions** tab → wait about 5 minutes → open the run →
download **FocusGuard-apk**.

**B. Android Studio (PC):** open this folder → wait for sync → **Build ▸ Build App Bundle(s)/APK(s) ▸ Build APK(s)**.

## 2. Install & make it "Device Owner" (one-time, about 10 minutes)
Device Owner is what makes uninstalling impossible without the 24-hour wait. Android only allows it
when **no accounts are on the phone**, so:

1. Settings ▸ Accounts → **remove every account** (Google, Samsung, Xiaomi, WhatsApp backup etc.).
   Your data stays on the phone, and you add the accounts back in step 6.
2. Settings ▸ About phone → tap *Build number* 7× → Developer options → turn on **USB debugging**
   (Xiaomi: also turn on "USB debugging (Security settings)").
3. Install the APK on the phone.
4. On your PC, install Google "SDK Platform-Tools" (adb), plug in the phone, then run:
   ```
   adb shell dpm set-device-owner com.spirit.focusguard/.AdminReceiver
   ```
   You should see "Success: Device owner set". If it says "already some accounts", go back to step 1.
5. Open FocusGuard → add any extra sites → **Activate protection** → accept the VPN prompt.
6. Add your Google and other accounts back.
7. **Test it for a day.** Then tap **Lock developer options** so USB/adb can't be used to bypass it.

## 3. Removing it later
Open FocusGuard → Request uninstall → after 24h tap **Yes, do it now** → Settings ▸ Apps ▸ FocusGuard ▸ Uninstall.

## Honest limits
- A full wipe from the **hardware recovery menu** (power + volume buttons) still erases the phone,
  and the app with it. Nothing on Android can stop that, but it means losing everything on the phone, which is a big hurdle.
- Some websites slip past Cloudflare's adult filter. Add them to your list when you spot them.
- Browsers or apps with their own built-in VPN or proxy (Tor, some "free VPN" browsers) can get around DNS filtering.
  Other VPN apps can't connect while FocusGuard holds the VPN slot.
- The Chrome settings (no incognito, SafeSearch) only apply to Chrome. The DNS filter covers all apps.
