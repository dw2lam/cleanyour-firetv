# Fire TV setup

Debloat plus the Apple TV-style **My Home** launcher for **Amazon Fire TV** smart TVs and sticks, as set up on
David's TV (Fire TV AFTALMO, Fire OS 7.7.1.5) on 2026-10-21. Upstream ([Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack))
calls Fire TV "debloat only", because Fire OS will not hand Home to another launcher; this setup gets around that with
the Home redirect from [andrewsaad/fire-tv-cleanup](https://github.com/andrewsaad/fire-tv-cleanup), tested on the same
model and firmware.

| What | Where |
|---|---|
| Set up a new Fire TV with Claude Code | [`firetv/PROMPT.md`](PROMPT.md) |
| Install / re-install everything | [`firetv/install.sh`](install.sh) |
| Undo everything | [`firetv/restore.sh`](restore.sh) |
| The debloat list (what is disabled, and why) | [`examples/amazon-fire-tv-aftalmo/debloat.txt`](../examples/amazon-fire-tv-aftalmo/debloat.txt) |
| Launcher config (dock, greeting, columns) | [`launcher/app/src/main/res/values/config.xml`](../launcher/app/src/main/res/values/config.xml) |
| Cinejoy kiosk app | [`firetv/cinejoy/`](cinejoy/) |

## Setting up another Fire TV

1. On the TV: Settings > My Fire TV > About > press OK on the TV's name 7 times, then back > Developer options >
   **ADB debugging on**. Note the TV's IP (Settings > Network).
2. Easiest: open Claude Code in this repo and paste [`PROMPT.md`](PROMPT.md). It backs the TV up first, checks the
   list against your model, asks which apps you use, and verifies each step on screen.
3. By hand, on a Mac with JDK 17 and the Android SDK (platform 34):
   ```bash
   python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
   TV_HOST=192.168.1.50 firetv/install.sh        # accept "Allow USB debugging?" on the TV
   ```
   The list is for the AFTALMO on Fire OS 7.7; on another model check it first (`LIST=` picks another
   `examples/*/debloat.txt`), since a package that is harmless on one TV can carry inputs on another.
4. On the TV, once: Amazon home > Apps grid > highlight **My Home** > Menu > **Move to front**. The redirect taps
   that tile, which is what makes Home fast.
5. Switch ADB debugging off again (see Closing ADB).

## What you get

- **My Home**: pitch black, a dock (Cinejoy, Prime Video, AirPlay, Netflix, YouTube, Spotify on David's TV), the rest of
  the apps below it, a greeting with your name, a thin clock and the date, Wi-Fi and Settings pills, one glass
  highlight, an ambient glow in the focused app's colour.
- **Home button**: Fire OS does not let any app replace Amazon's home, so the launcher's *Home redirect* (an
  accessibility service) watches for Amazon's home screen and taps My Home's tile in Amazon's apps row. Amazon's
  screen shows for a moment first: about 1 s when idle, several seconds after a heavy app like Netflix.
- **Live button = My Home**, about 2 s from any app. Fire OS handles its remote's buttons before any app sees them,
  so Live still starts Amazon's TV guide; the redirect spots the guide (useless without an antenna) and opens My
  Home over it. Change it with `launcher_trigger_windows` in `config.xml`.
- **AirPlay tile**: Fire OS lets only Amazon's own apps open the AirPlay app, so the tile shows a short panel on how
  to cast to the TV, and a Settings button. AirPlay itself needs nothing opened: casting from an iPhone or Mac takes
  over the TV.
- **No Inputs pill**: switching HDMI inputs is Amazon-only on Fire OS (its input player needs a signature
  permission), so use the remote's Input button.
- **Clock screensaver** after 5 minutes, in place of Amazon's sponsored one.
- **Debloat**: ads, shopping, recommendations, telemetry, Alexa, Luna, Silk, Amazon Music/Photos/Kids, phone casting
  and Miracast, channel scan, the forced-update nag, tutorials and stubs (108 packages on the AFTALMO). Kept:
  streaming apps, AirPlay, the Appstore, firmware updates, Settings, HDMI inputs, remote and Bluetooth.
- **AdGuard Private DNS**: ad and tracker domains stop resolving system-wide. It does not block YouTube ads, and the
  banner at the top of *Amazon's* home stays (it comes from Amazon's own feed).

## Cinejoy

`cinejoy.pk` full screen with no browser UI (`firetv/cinejoy`, package `online.davidlam.cinejoy`). Pop-ups and
redirects to other sites are blocked; the video players inside the page are not restricted.

- **Menu** switches between two ways to move (a label shows which; the choice is kept), **hold Menu** to reload:
  - **Select** (default): the arrows jump between the page's links and buttons with a lime focus ring, like a TV
    app; OK opens. The first press starts on a Play button when there is one; Down then Up comes back to the same
    tile; the focus clears on every new page.
  - **Cursor**: the arrows move a pointer (hold to speed up), OK taps where it is, pushing against an edge scrolls
    what is under it.
- **Player** (a video filling the screen, in either mode's Select view): OK or Play/Pause pauses and plays,
  Left/Right skip 10 s, Rewind/Fast-forward 30 s, Up/Down bring up the site's own controls and move between them
  (Down past the last one returns to the player). A time readout shows each change. The screen stays on while a
  video plays.
- **Back** goes back (or leaves fullscreen video, or closes the keyboard).
- On this TV a Select-mode key takes about 10-30 ms (`assets/nav.js` checks only the few best candidates in
  full; scanning every link with a hit test took ~180 ms).

## Everyday things

- **TV settings**: the Settings pill. **Launcher settings**: hold OK on the Settings pill (tiles per row,
  animations, Edit dock, Reorder apps, Use Amazon Home Screen).
- **Back to Amazon's home screen for good**: hold OK on Settings > *Use Amazon Home Screen*; the same menu then says
  *Use This Home Screen*.

## Re-enable an app

Everything is *disabled*, never uninstalled, so it comes back with one command. With ADB debugging on:

```bash
TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/tv.py 'pm enable com.amazon.cloud9'   # e.g. Silk
TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/tv.py 'pm list packages -d'           # what is disabled now
```

Each line of `debloat.txt` says what the package is; comment it out there so a re-run of `install.sh` leaves it
alone. To undo the whole thing: `TV_HOST=... firetv/restore.sh` (`KEEP_APPS=1` keeps My Home and Cinejoy).

## Change the dock order

- **On the TV**: hold OK on the Settings pill > *Edit dock*: pick a dock place, then the app to put there; Back to
  finish. *Reorder apps* moves tiles in the grid. These choices stay on the TV.
- **In the build**: edit `dock_apps` (package names, in order, up to `columns`) and `greeting_name` in
  `config.xml`, then `TV_HOST=... launcher/build-and-install.sh`.

Package names: Cinejoy `online.davidlam.cinejoy`, Prime Video `com.amazon.firebat`, AirPlay
`com.apple.airplay.amazon.airplay`, Netflix `com.netflix.ninja`, YouTube `com.amazon.firetv.youtube`, Spotify
`com.spotify.tv.android`, Disney+ `com.disney.disneyplus`, Downloader `com.esaba.downloader`.

## Change the wallpaper

Push a *theme*: a folder with a `config.json` and a `wallpaper.jpg` (1920x1080, dark works best), no rebuild:

```bash
mkdir -p mytheme && echo '{ "wallpaper": true }' > mytheme/config.json   # everything else from config.xml
cp ~/Pictures/my-wallpaper.jpg mytheme/wallpaper.jpg
TV_HOST=192.168.1.50 tools/theme.sh mytheme            # push and restart Home (the redirect stays on)
TV_HOST=192.168.1.50 tools/theme.sh themes/aurora      # or a ready-made one: aurora, scenes, ...
TV_HOST=192.168.1.50 tools/theme.sh --reset            # back to the built-in look
```

A theme's `config.json` can set the other keys in [`themes/README.md`](../themes/README.md) too; a key it leaves out
keeps the value from `config.xml`. `themes/default` also sets upstream's sample dock, so don't push that one as is.

## After a reboot or a Fire OS update

- Fire OS turns **Alexa** (`com.amazon.vizzini`) back on at every boot; everything else stays disabled (checked
  after a full restart). Re-running the list turns Alexa off until the next boot.
- An update can re-enable packages or switch accessibility services off: re-run `firetv/install.sh`.
- Amazon caches app banners by version: bump `versionCode` in `launcher/app/build.gradle` after changing the
  launcher's banner, or Amazon's apps row keeps the old one.
- If Home stops coming back to My Home, check that **My Home is first** in Amazon's apps row (step 4 above).

## Closing ADB

Network ADB (port 5555) gives a shell on the TV to whoever the TV has trusted, so switch it off when not in use:
Settings > My Fire TV > Developer options > **ADB debugging off**, then restart the TV. Check it is closed with
`tools/venv/bin/python -I tools/adb-closed.py <tv ip>` (only "Connection refused" proves it).
