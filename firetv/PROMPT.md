# The Fire TV prompt

Open Claude Code (or another coding assistant with shell access) in this repo, on a computer on the same network
as the TV, and paste the part below the line. First, on the TV: Settings > My Fire TV > About > press OK on the TV's
name 7 times, back > Developer options > ADB debugging on.

---

Set up my Amazon Fire TV like the reference setup in `firetv/README.md`: debloat it, install the My Home launcher
with its Home redirect, the Cinejoy kiosk app and AdGuard DNS. The TV's name or IP: <TV NAME OR IP HERE>.
This repo has everything already built for it; reuse `tools/`, `launcher/`, `firetv/` and the
`examples/amazon-fire-tv-aftalmo/debloat.txt` list instead of writing new ones. Work on the real device, verify every
step with screenshots from the TV (`tools/tv.py --screenshot`), and ask me before anything that depends on my
preferences.

Connect
- Find the TV by its mDNS name (`dns-sd -B _airplay._tcp` or `_amzn-wplay._tcp`, then resolve it) or ask me for the
  IP; don't port-scan the network, it may be shared. Connect over ADB on port 5555 and tell me when to accept the
  "Allow USB debugging?" prompt. If the stock adb says "No route to host" on macOS, use `tools/tv.py` instead.
- Read the model, Fire OS version (`ro.build.version.name`), SDK and RAM, and tell me.

Back up first
- Save the package lists (all, disabled, system, third-party), getprop, all three settings tables, the HOME
  activities and the accessibility/IME/DNS/screensaver settings to `backup/<date>-<tv>/` (git-ignored), and write a
  `restore-all.sh` there that re-enables everything disabled after the backup.

Debloat
- Compare the TV's packages with `examples/amazon-fire-tv-aftalmo/debloat.txt`. Packages it doesn't know: check them
  with `dumpsys package` (and the APK's label with `aapt2 dump badging`) before listing them; never launch a factory
  or test app to find out. Never touch Amazon's launcher, firehomestarter, settings, Live TV, the
  droidlogic/mediatek input packages, IME, remote/Bluetooth, Wi-Fi, account, Appstore or OTA.
- Ask me with checklists which apps I use (Prime Video, Silk, Amazon Music/Photos, Luna, Downloader, ...), whether to
  keep Alexa, AirPlay, phone casting/Miracast, the antenna/Live TV, and forced updates; disable only what I untick.
  Note: `com.amazon.spiderpork` is the Amazon Luna app.
- Apply the list in small batches with `tools/disable.sh`, screenshot Home after each, and check that Netflix,
  Prime Video, Settings, the Appstore, Wi-Fi and AirPlay (`dns-sd -B _airplay._tcp`) still work. Mark packages Fire OS
  refuses ("protected") as such in the list. Keep the Clock screensaver change for after the launcher is in.
- Offer AdGuard Private DNS (`firetv/install.sh` sets it); it doesn't block YouTube ads.

Launcher and apps
- Ask me which apps go in the dock (and in what order, up to 6), and my name for the greeting; set them in
  `launcher/app/src/main/res/values/config.xml`. Install apps I want from the Appstore by opening their page
  (`am start -a android.intent.action.VIEW -d "amzn://apps/android?p=<package>"`) and pressing OK on Get.
- Run `TV_HOST=<ip> SKIP_DEBLOAT=1 firetv/install.sh` (it builds and installs the launcher with
  `REDIRECT_HOME=1`, Cinejoy and DNS). Then pin My Home: Amazon home > Apps grid > My Home > Menu > Move to front
  (inject the keys and screenshot; pause the redirect with `settings put secure enabled_accessibility_services ''`
  while doing it, and turn it back on after).
- Verify with the real remote, not only injected keys: Home from inside an app lands on My Home (logcat
  `HomeRedirect: clicked our tile`), Live opens My Home (`trigger window`), the AirPlay tile shows its panel, the
  Clock screensaver runs (set `screen_off_timeout` to 15000 briefly, then back to 300000), Cinejoy loads.
- Things already known not to work on Fire OS, don't retry them: replacing Home (`set-home-activity` is ignored),
  switching HDMI inputs from an app, catching the Home or Live keys in an accessibility service, opening the AirPlay
  app.

Finish
- Reboot once with `adb reboot` (the power button only puts a Fire TV in standby), then compare `pm list packages
  -d` with the list; Alexa (`com.amazon.vizzini`) comes back at every boot, the rest should stay.
- Add the TV's own list to `examples/` if it differs, update `firetv/README.md`, then tell me to switch ADB
  debugging off and check with `tools/adb-closed.py <ip>` that port 5555 refuses connections.
