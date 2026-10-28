# Chameleon 🦎

Rotates your Samsung Gallery **favourites** as the home screen wallpaper and makes
One UI's colour palette follow each new image.

One UI does not recompute the palette when another app changes the wallpaper.
This app works around it: after setting the wallpaper it opens **Wallpaper and
style → Colour palette**, picks the first palette suggested for the new image and
taps **Apply**, using an accessibility service. Buttons are matched by resource
ID, so it works whatever the phone's language.

## Install

1. Push this repo to GitHub. The *Build APK* workflow runs on every push.
2. Open the workflow run in the **Actions** tab and download the
   `chameleon-apk` artifact (a zip containing `app-release.apk`).
3. Install it: `adb install -r app-release.apk` (or copy it to the phone and open it).

## Set up

1. Open **Chameleon** and tap *Allow photo access* — choose **Allow all**,
   otherwise favourites in other folders (WhatsApp etc.) are invisible.
2. Tap *Turn on the accessibility service* and enable **Chameleon**.
   One UI may first ask you to allow restricted settings for sideloaded apps:
   Settings → Apps → Chameleon → ⋮ → *Allow restricted settings*.
3. Samsung only lets apps holding `WRITE_SECURE_SETTINGS` open Wallpaper and
   style. Grant it once from a computer (it survives updates and reboots):
   `adb shell pm grant fr.guerard.palettewall android.permission.WRITE_SECURE_SETTINGS`
4. Switch off any other wallpaper changer, and make sure the current wallpaper is
   a normal image, not a live wallpaper.
5. Tap *Change wallpaper now* to test the whole flow.

After that it runs by itself, at most once per interval (default 120 min): the
picture changes when the screen turns off, and the colours are refreshed right
after you unlock, behind a cover showing the new wallpaper. Favourites are
shuffled, and each is shown once before any repeats.

## Debugging

```bash
adb logcat -s Chameleon
```

If the palette screen layout changes after a One UI update, dump it again with
`adb shell uiautomator dump` and update the IDs in `PaletteService.kt`.
