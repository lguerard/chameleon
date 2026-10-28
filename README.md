# Palette Wallpaper

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
   `palette-wallpaper-apk` artifact (a zip containing `app-debug.apk`).
3. Install it: `adb install -r app-debug.apk` (or copy it to the phone and open it).

## Set up

1. Open **Palette Wallpaper** and tap *Allow photo access* — choose **Allow all**,
   otherwise favourites in other folders (WhatsApp etc.) are invisible.
2. Tap *Turn on the accessibility service* and enable **Palette Wallpaper**.
   One UI may first ask you to allow restricted settings for sideloaded apps:
   Settings → Apps → Palette Wallpaper → ⋮ → *Allow restricted settings*.
3. Samsung only lets apps holding `WRITE_SECURE_SETTINGS` open Wallpaper and
   style. Grant it once from a computer (it survives updates and reboots):
   `adb shell pm grant fr.guerard.palettewall android.permission.WRITE_SECURE_SETTINGS`
4. Switch off any other wallpaper changer, and make sure the current wallpaper is
   a normal image, not a live wallpaper.
5. Tap *Change wallpaper now* to test the whole flow.

After that it runs by itself on unlock, at most once per interval (default 120 min).
You'll see Wallpaper and style open for a second or two, then the home screen.

## Debugging

```bash
adb logcat -s PaletteWall
```

If the palette screen layout changes after a One UI update, dump it again with
`adb shell uiautomator dump` and update the IDs in `PaletteService.kt`.
