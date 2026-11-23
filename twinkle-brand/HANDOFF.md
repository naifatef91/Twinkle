# Twinkle — brand assets handoff

Instructions for Claude Code: apply the Twinkle logo and app icon in this project.
Twinkle is a file-transfer-between-devices app. These assets are final; do not redraw,
recolour or re-rasterise them from memory — use the files in this folder.

## Task

1. Detect the project type (native Android, Flutter, React Native, Electron, web/PWA, …)
   and list every place the current app icon, logo, splash and app name are defined.
   Show me that list before changing anything.
2. Replace the launcher / app icon on every platform the project targets (mapping below).
3. Replace in-app logo usages (splash, about screen, header, favicon) with the matching file.
4. Set the display name to `Twinkle` where the app name is shown to users.
   Do NOT change the package id / bundle id / applicationId.
5. Build, and report which files you changed. Do not commit unless I ask.

## Colours

| Role | Hex |
|---|---|
| Brand indigo (icon background, text on light) | `#1E1B4B` |
| Twinkle yellow (main star) | `#FFD23F` |
| White (small spark, text on dark) | `#FFFFFF` |
| Light surface | `#F4F3FB` |
| Secondary text on light / on dark | `#55527A` / `#C9C7E8` |
| Mono-dark tile | `#0B0A1F` |

Wordmark typeface: Outfit SemiBold (600), letter-spacing −0.022em. The wordmark in the
SVG/PNG logos is already converted to outlines, so no font is needed to display them.
Load Outfit only if the app renders the name as live text.

## Files

### `svg/` (source of truth)
- `icon-square.svg` — full-bleed square, no rounded corners. Use for stores, iOS, and any generator that applies its own mask.
- `icon-rounded.svg`, `icon-circle.svg` — pre-masked versions for places that do not mask.
- `icon-mono-light.svg`, `icon-mono-dark.svg` — single-colour versions.
- `adaptive-foreground.svg`, `adaptive-monochrome.svg` — transparent Android adaptive layers (mark inside the safe zone). Background layer is the flat colour `#1E1B4B`.
- `mark-color.svg`, `mark-indigo.svg`, `mark-white.svg` — the star mark alone, transparent.
- `logo-light.svg` — icon tile + indigo wordmark, for light backgrounds.
- `logo-dark.svg` — mark + white wordmark, for dark backgrounds (use on `#1E1B4B` or darker).
- `wordmark-indigo.svg`, `wordmark-white.svg` — the name alone.

### `png/`
- `icon-square-{1024,512,192,180,144,96,72,48,32,16}.png`
- `icon-rounded-*.png`, `icon-circle-*.png` at 512, 192, 96, 48
- `adaptive-foreground-432.png`, `adaptive-monochrome-432.png`
- `logo-light.png`, `logo-dark.png` (440 px tall, transparent)

### `android/res/` (drop-in)
Copy the contents into `app/src/main/res/`, merging folders:
- `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` — adaptive icon with themed (monochrome) layer
- `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml` — vector layers
- `values/ic_launcher_background.xml` — `@color/ic_launcher_background`
- `mipmap-{mdpi…xxxhdpi}/ic_launcher.png`, `ic_launcher_round.png` — legacy fallbacks

Delete any old `ic_launcher*.webp` / `.png` / foreground files that would shadow these, and
make sure `AndroidManifest.xml` has `android:icon="@mipmap/ic_launcher"` and
`android:roundIcon="@mipmap/ic_launcher_round"`.

## Platform mapping

- **Native Android**: use `android/res/` as above. Play Store listing icon: `png/icon-square-512.png`.
- **Flutter**: if `flutter_launcher_icons` is present (or acceptable to add as a dev dependency — ask first),
  configure `image_path: png/icon-square-1024.png`, `adaptive_icon_background: "#1E1B4B"`,
  `adaptive_icon_foreground: png/adaptive-foreground-432.png`,
  `adaptive_icon_monochrome: png/adaptive-monochrome-432.png`, then run it. Otherwise copy `android/res/` manually.
- **iOS**: `png/icon-square-1024.png` into `AppIcon.appiconset` (no transparency, no rounded corners — iOS masks it).
- **Web / PWA**: `svg/icon-rounded.svg` as SVG favicon, `png/icon-square-32.png` fallback,
  `png/icon-square-180.png` as apple-touch-icon, 192 and 512 in the manifest
  (`icon-square-*` with `"purpose": "any maskable"`), `theme_color` `#1E1B4B`.
- **Desktop (Electron/Tauri/Windows)**: generate `.ico` / `.icns` from `png/icon-rounded-512.png`
  (or `icon-square-1024.png` for macOS, which expects its own shape).

## Rules

- Keep clear space around the logo of at least the height of the small spark.
- Never stretch, rotate, recolour outside the palette, or add effects (shadow, glow, gradient).
- On photos or mid-tone backgrounds use the icon tile (`logo-light.svg` / `icon-rounded.svg`), not the bare mark.
