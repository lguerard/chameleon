# Chameleon — project context

Android app (repo `palette-wallpaper`, package `fr.guerard.palettewall`, kept
for upgrade compatibility) for Laurent's Galaxy S23 Ultra on One UI 9 /
Android 17. It rotates his Samsung Gallery **favourites** as the wallpaper and
makes One UI's colour palette follow each picture.

## Why it works the way it does (all verified on the phone)

- Samsung Gallery favourites are exposed through the standard MediaStore flag
  `is_favorite = 1`, readable with `READ_MEDIA_IMAGES` ("Allow all").
- One UI does **not** recompute the palette when another app sets the
  wallpaper (WallpaperManager), nor for live wallpapers (it uses a thumbnail).
- Samsung's "set as wallpaper" screen refuses adb calls
  ("restricted adb call") and, called from an app, still doesn't refresh the
  palette. Dead end.
- What works is driving **Wallpaper and style → Palette de couleurs** with an
  accessibility service, in this exact order: switch palette **off → Appliquer
  → on**, set "Applic. palette aux icônes appli.", pick **Couleur 1** on the
  "Couleurs fond écran" tab → **Appliquer**. Anything shorter leaves the system
  UI on the old colours.
- That screen is `com.samsung.android.app.dressroom/.presentation.settings.WallpaperSettingActivity`.
  dressroom has no launcher entry, and the activity requires
  `WRITE_SECURE_SETTINGS`, granted once with
  `adb shell pm grant fr.guerard.palettewall android.permission.WRITE_SECURE_SETTINGS`.
- Nodes are matched by resource ID (`color_icon`, `color_theme_switch_bar`,
  `switch_toggle`, `switch_app_icon`, `color_list_tab_layout`,
  `color_palette_container`, `bottom_button`), from uiautomator dumps, so the
  flow doesn't depend on the UI language (French).

## Current behaviour

- Screen off (once per interval, default 120 min): set the next favourite from
  a shuffled no-repeat queue, centre-cropped and scaled to the full physical
  screen size (One UI ignored crop hints), and mark the palette as pending.
- Unlock: if pending, run the palette flow behind an accessibility overlay
  showing the new wallpaper; each step polls the screen instead of fixed delays;
  25 s timeout; then go home and remove the overlay.
- Options in the app: auto on/off, lock screen too, icons too, hide flash,
  interval; buttons "Change wallpaper now" and "Only re-apply the palette".

## Build

No local Android SDK assumed. GitHub Actions (`.github/workflows/build.yml`)
builds the minified release APK, only on `v*` tag pushes, with Gradle 8.11.1 / AGP 8.7.3, signed (release too) with the committed
`app/debug.keystore` so updates install over each other. Artifact:
`chameleon-apk`. Install with `adb install -r app-release.apk`.

## Debugging

`adb logcat -s Chameleon`. After a One UI update, re-dump the screens with
`adb shell uiautomator dump` and check the IDs in `PaletteService.kt`.

## Commits and releases

Commits must be conventional, because git-cliff (`cliff.toml`) builds the
release notes from them: `<type>(<scope>): <icon> <short description>`, first
line under 78 characters. Types that appear in the notes: `feat`, `fix`, `perf`,
`refactor`, `docs`, `ci`, `build`; `chore`, `style`, `test`, `wip` are skipped.
Release: `git tag vX.Y.Z && git push origin vX.Y.Z` builds the APK and publishes
a GitHub release whose notes come from `git cliff --latest`.
