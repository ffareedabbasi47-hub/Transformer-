# STSMN AURELION - UI redesign report

Presentation layer only. No change to: SettingsStore/AppSettings, repositories, EspClient/RealtimeLink, JSON parsing, risk/alert logic,
domain classes, ESP32 firmware, server, endpoints or permissions. Appearance choices live in their own SharedPreferences file `stsmn_ui`.

## Changed UI files
`ui/Aurelion.kt` (new: tokens, appearance prefs, shimmer), `ui/Theme.kt`, `ui/Widgets.kt`, `ui/Components.kt`, `ui/DashboardScreen.kt`,
`ui/SplashScreen.kt`, `ui/StsmnApp.kt` (floating nav + theme provider), `ui/HistoryScreen.kt`, `ui/UserScreen.kt`, `ui/SettingsScreen.kt` (Appearance section),
`ui/DiagnosticsScreen.kt` (monospace values), plus the earlier map UI (`EarthScreen`, `MapScreen`, `TransformerSheet`, `NativeGlobe`).

## Tokens (dark)
Background #090909 / raised #111315 / high #191B1E / outline #25282D; gold #C6A76A; platinum #B8C0C8; emerald #4C9B78; amethyst #9C78C9;
text #F1EEE5 / #A7A39B; Safe #4C9B78, Warning #D6A34F, Emergency #D16B70. Light theme = ivory (#F4F1EA) with the accent darkened for contrast.

## User-selectable (Settings > Appearance)
12 colour themes (Emerald Dark, Sage & Grey, Claude, Cyberpunk, Obsidian Gold, Deep Ocean, Sunset, Nordic Frost, Mocha, Royal, Clean Ivory, System) · Text size (Small 0.85x, Medium 1.0x, Large 1.18x, Huge 1.35x) · Corners · Reduce motion · Reset.
Reduce motion · Reset. Light/Dark/System stays in Display. All choices apply immediately and are remembered.

## Not done / limits
* Manrope, Inter, Space Grotesk and JetBrains Mono are NOT bundled (the build sandbox could not download font files). The app uses system sans-serif,
  tabular numerals and system monospace; `AurelionType` is the single place to switch to bundled fonts later. No Devanagari font set.
* Reduce motion covers the nav bar, status pulse and shimmer; the 3D globe camera glides and panel transitions still animate.
* Fleet, Sensors and Connect screens inherit the new tokens/components but were not individually re-laid-out.
* Not verified on a physical phone; CI (unit tests + debug build) only.
