# Map sprint – feature-wise test report

Commit 629f4e4. "CI" = GitHub Actions (JVM unit tests + Gradle debug build). **No physical-device or emulator test has been run.**
No screenshots or screen recording exist for these features: none could be produced without a device.

| # | Feature | Implemented | Automated test (CI) | Verified on a phone |
|---|---------|-------------|---------------------|---------------------|
| 1 | Layers: Satellite / Terrain / Streets + Roads overlay, layer panel, per-layer attribution | yes (`MapScreen.kt`, `domain/MapLayers.kt`) | tile-URL format and attribution text (`MapFeaturesTest`) | NO |
| 2 | Search by ID / label / name over the real fleet list (`TR-001`, `tr001`, `stsmn-tr-001`), tap result flies to it | yes (`EarthScreen.kt`, `domain/FleetFilter.kt`) | 5 tests | NO |
| 3 | SAFE / WARNING / EMERGENCY / OFFLINE(+STALE) filter chips with counts; applied to globe and map | yes | 3 tests | NO |
| 4 | Clustering at mean position (Web-Mercator), worst state colour, tap zooms into members, selected marker never merged | yes (`domain/Clustering.kt`, `MapScreen.kt`) | 6 tests (exact single position, merge/split by zoom, far apart, centre between members, worst state, order independence) | NO. With one transformer no cluster ever appears. |
| 5 | Map -> globe: "Globe" flies out from the map's last centre (cross-fade + glide) | yes (`EarthScreen.kt`, `NativeGlobe.kt`) | none (UI/animation) | NO |
| 6 | Full screen: hides bottom bar and system bars, Exit button, Back exits | yes (`StsmnApp.kt`, `EarthScreen.kt`) | none (UI) | NO |
| 7 | Transformer bottom sheet: ID, full name, status, DEMO vs configured coordinates, LIVE/NOT LIVE, last update, key readings, expandable sensor grid, actions | yes (`TransformerSheet.kt`) | none (UI) | NO |

## Known limits / not done
- "Configured coordinates" is shown for non-demo positions: the app has no way to prove a position is a verified GPS fix.
- Terrain = Esri World Topographic Map (a drawn topo map, not 3D relief). Roads = Esri World Transportation reference tiles. Both need internet; areas not visited are blank offline.
- Layer panel, search panel and sheet were laid out blind; overlaps on small screens are possible and untested.
- The globe itself still uses a z=3 picture (soft); detail comes from the flat map.
- Existing globe rotation, double-tap zoom and Fly to site code paths were left unchanged.
