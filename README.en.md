# D7 (七日) · Sunlight Duration Calculator

Measure the sunlight of a home before you buy it, with nothing but your phone.

Stand at the window you care about, aim the camera at the rooftop corners of the buildings across from you, and tap the shutter for each one. The phone records the elevation and azimuth of every point, connects them into a skyline, and computes the direct sunlight hours for that window on every day of the year.

No tape measure, no rangefinder, no need to know how tall the buildings are or how far away. Works best for existing homes and resale apartments you can physically visit.

> All four development phases (algorithm prototype, capture app, computation and visualization, polish and release) are implemented. This file explains the principle and the workflow. For Chinese users, see [README.md](README.md).

## The idea in three sentences

1. The sun's daily arc across the sky depends on only two things: your latitude and the date. In Beijing the midwinter sun peaks at about 27 degrees above the horizon, the midsummer sun at about 74 degrees. Astronomy has this nailed down to a tiny fraction of a degree, far beyond the accuracy of any phone sensor.
2. A building blocks the sun by angle alone. A 100 m tower 500 m away and a 10 m wall 50 m away cover the same patch of sky as seen from your window, and cast the same shadow over it. A home buyer needs an angle-measuring tool, not a rangefinder.
3. A phone can measure those angles. The attitude sensor references gravity for elevation, good to about 1 degree. The compass, corrected for magnetic declination, gives azimuth to about 2 to 4 degrees. The camera is just the viewfinder: put the crosshair on a rooftop corner, tap the shutter, and the angle is recorded.

Once the skyline points are captured, the app steps through each day minute by minute, computes the sun's position, and checks it against the skyline. Every minute with the sun above the skyline counts as direct sunlight. Sum the minutes for the day's total. Repeat across dates for the year-long curve.

Azimuth runs clockwise from true north, 0 to 360 degrees, so due south is 180. Elevation is the upward angle, 0 at the horizon and 90 at the zenith.

![Principle diagram](img/principle.svg)

Buildings often have gaps between them. When you reach the end of a building, long-press to close it: the app drops vertically from that point to the horizon, runs along the horizon to the next point's direction, and rises vertically to it, so a gap is formed in one gesture. In the diagram above, a gap between two southern buildings lets the midwinter sun shine through from about 13:40 to 14:40, a full hour that the roofline alone would have blocked. So land points on both edges of every gap, and short-press any low wall or tree standing inside it into the outline, or it will quietly steal that hour. The figure labels are in Chinese.

### Southern hemisphere and the tropics

The sun's overhead point migrates between the Tropic of Cancer and the Tropic of Capricorn over the year. So "the blocking buildings are to the south" only holds north of the Tropic of Cancer.

North of the Tropic of Cancer, which covers most of China, the midday sun is always in the south. Capture the southern skyline. South of the Tropic of Capricorn, think Sydney, Auckland or Buenos Aires, the midday sun is always in the north. Capture the northern skyline.

Between the two tropics, think Haikou, Sanya or Singapore, it is mixed: the midwinter sun culminates in the south while the midsummer sun passes through the north. Capture both sides, ideally a full circle. The app suggests the priority direction from your latitude and warns about uncovered azimuth sectors.

One subtlety applies even in Beijing: in summer the sun rises in the northeast and sets in the northwest. A southern skyline alone cannot rate early mornings and late evenings around the June solstice. Buyers mostly care about winter, and in winter the sun stays in the southern half of the sky all day. In Beijing on the winter solstice the sun rises at azimuth 120 and sets at 240. Cover the south plus part of the east and west, and the winter numbers are trustworthy.

## How to use it

1. Position and groups. Stand at the window or balcony you care about, phone as close to the middle of the window opening as you can reach. One window is one group: name each new group on creation (e.g. "balcony"), then type latitude/longitude or tap Use GPS — the dialog shows this device's live location capability and accuracy (GPS, network, system fused) and falls back silently to manual entry when location is unavailable. Build separate groups for separate windows and floors. Angles follow the standing point. You measure where you stand. Off-plan homes cannot be measured, this tool serves homes you can visit.
2. Calibrate. Wave the phone in a figure-eight to calibrate the compass on site. Rebar in balconies and window frames disturbs the magnetometer. Do not skip this. The app shows its current heading accuracy.
3. Capture. Face the southern buildings and shoot their outline vertices in order, from the right (west) to the left (east). In the southern hemisphere mirror it: face north and go from the right (east) to the left (west). Either way, walk from one end to the other without jumping back and forth, so that flipping through the album from right to left follows the shooting direction from old to new. The shutter has two presses: a short press connects the new point to the previous one with a straight segment; a long press closes the current building at the previous point. The app then drops vertically from the previous point to the horizon, runs along the horizon to the new point's direction, and rises vertically to the new point, so a gap is formed in one gesture. If a low wall, tree or low building inside a gap blocks light, do not long-press over it; short-press its top corners into the outline instead. Each shot auto-saves its photo, no toggle; single shots only, no burst.

Each group holds two zones: the external-building zone for the towers across the street, and the ceiling zone for your own window-frame head. The zone chip at the top left of the viewfinder switches between them; point order is independent per zone and photos are stored per zone. In the ceiling zone, shoot along the frame head in the same right-to-left order (top-right corner, midpoints, top-left corner, plus extra points wherever beams or soffits step), down each jamb to the side reveal; the sill below needs nothing, it is assumed open. The ceiling zone supports short press only, no long press: a frame is continuous and has no gaps, so a long press does nothing there.
4. Fix and finish. No need to redo a bad point: the delete key at the bottom right removes only the captured point nearest to the current heading on the right side of the crosshair, never anything on its left; it fires only on long press, and a single hold deletes at most 1 point. The bottom-left done key confirms the session. Check the coverage bar for misses (it scores only the southern half, 90–270°, mirrored to the northern half in the southern hemisphere); capture nearby walls, trees and low buildings to the sides while you are at it. Ground floors should pay extra attention to close-in obstructions.
5. Resume and edit points. Going back from results to capture resumes shooting on the same group: pick the zone with the chip first, and new points append to that zone's order. Editing captured points lives in the results screen's point list: delete any point, or switch the segment between a point and its neighbor to the right (the previous shot, i.e. the row above in the list) between "direct connect" and "via horizon" modes (ceiling points are direct only).
6. Read the results.

All groups live on the group management screen, one card per group: the name, the solstice verdict, and each zone's state (external point count with coverage, ceiling point count or an empty dashed box). Tap a card for results, long-press to rename, delete or export; creating a group from the top-right button names it and jumps straight into capture.

![Group management](img/ui-groups.svg)

The viewfinder carries live reference arcs: blue dashed for the winter solstice sun path, the lowest of the year; red dashed for the equinoxes, in between; orange dashed for the summer solstice, the highest, usually off the top of the screen until you tilt the phone up. A white solid line marks today's sun path, for same-day checks. The zone chip at the top left of the viewfinder switches the active capture zone between external buildings and ceiling. The horizon and plumb lines are gray short-dashed and hidden by default; switch them on in the line settings when needed. Whichever segment falls inside the current field of view is the one drawn. When the crosshair sits below the winter-solstice arc, that point never blocks the midwinter sun and can be ignored. Above the summer-solstice arc, the point blocks the sun all year round. Near the zenith at low latitudes the projection loses accuracy, so that segment is hidden and replaced with a text hint. Whenever the aiming angle crosses either boundary, the screen shows a live reminder. Right of the accuracy pills sits the roll readout (how far the phone tilts sideways, not a heading; positive = top edge leaning right), handy for levelling the phone.

Next to the azimuth readout sits a small `+180°` pill. The rear-camera sight direction is naturally about 180° off the body-orientation sensor reading when the phone is held upright (one points at the building, the other at the user), so +180° is applied by default and shown filled; tap once to remove or restore it, e.g. for landscape holding, clip-on lenses, or when the heading looks reversed. No burst mode: confirm point by point with single shots.

The viewfinder draws the captured points' connections directly: white solid for short-press measured segments, moon-gray dashed for long-press via-horizon inferred segments, so connected and broken runs read at a glance. The shooting-direction arrow sits below the viewfinder (west-first on the right, east-later on the left), never covering the frame center.

The "lines" control next to the coverage bar toggles each reference overlay: solstice, equinox and today's paths plus point connections are shown by default; the horizon and plumb lines are hidden by default and can be switched on when needed.

![Capture screen](img/ui-capture.svg)

## Reading the results

Six things on the results screen.

Computation scope. Below the date pills, three modes: external only (default, counts just the towers across the street), external + ceiling (also applies your own frame's cropping, the handover-inspection mode), ceiling only (how much light the bare opening admits, for judging the unit type before the towers). The rules: azimuths never captured in the external zone count as open; azimuths never captured in the ceiling zone count as solid wall; a zone with no points at all degrades by the same rule (empty external = fully open, empty ceiling = fully blocking), so the two ceiling-dependent modes stay disabled until the ceiling zone has points.

The year curve. One point per day, direct sunlight hours on the vertical axis. Low in winter and high in summer is normal. Look at the deepest dip of the winter.

Key dates. The solstices and equinoxes are preset, plus any date you pick. Buyers in mainland China can check the statutory dates used by the national residential planning standard GB 50180-2018, which scores homes on a standard day within an effective time window in local apparent solar time, commonly 2 hours on Dahan (around January 20) between 8:00 and 16:00 for large cities, or 1 hour on the winter solstice depending on the climate zone. The app ships with these presets so you can compare against local planning rules. The same logic works anywhere on Earth with any date you choose.

The timeline. A horizontal bar from sunrise to sunset. White means direct sun, dark gray means blocked while the sun is up. The same white also marks the sun that passes between buildings. You see at a glance which hours have sun and which do not.

Clock time. The app converts sun positions using GPS longitude and the equation of time, then reports in your local timezone. Solar noon in Urumqi correctly lands around 14:00 Beijing time, not 12:00.

Points. One row per shot: zone tag (ext / ceil), index, azimuth, elevation, photo thumbnail, plus the connection mode between the point and its neighbor to the right (the previous shot, the row above in the list; direct / via horizon, switchable; ceiling points are direct only) and delete. Fix wrong points here before resuming capture.

![Results screen](img/ui-result.svg)

## Accuracy and limits

Listed honestly:

- Azimuth error of 2 to 4 degrees translates to roughly 10 to 20 minutes of timing error. Good enough to judge "does this window get two hours of sun", not good enough for litigation.
- Elevation error is about 1 degree and more reliable than azimuth. Since blocking is decided mostly by elevation, that is the good news.
- Trees are not modeled. Deciduous trees still block some light with bare winter branches. The tool assumes open sky, so results run optimistic. Treat evergreens as buildings and capture them too.
- Only astronomical direct sun is computed. Clouds, haze and glass attenuation are out of scope.
- You must measure from the window in question. A different window, or a different floor, changes the angles. Measure and save each one separately.
- This is not a replacement for an official sunlight analysis report. It is your own independent estimate, for cross-checking sales claims and comparing homes.

## Privacy

The app requests no network permission. Photos, angles and location stay on the phone; Android auto-backup is off (allowBackup=false), so nothing is uploaded to the cloud. Export to CSV and images anytime, delete anytime.

## UI and palette

The app is named D7 (七日, "seven days"). The icon source is `城市日照十字瞄准图标.png` in the repository root: black background, white crosshair, gray-white buildings, golden sun (an inspiration image and the palette reference `配色基准.jpg` are kept locally as design material, not committed).

The palette follows the lead character of `配色基准.jpg` (jet-black costume and background, mist-white lettering and highlights, gray-violet pupil gradient):

- Background black `#000000`: phone frame, capture / results background
- Card charcoal `#161618` / `#1C1C1E`: info cards, coverage track, blocked timeline segments
- Foreground white `#FFFFFF`: body text, crosshair, covered range, direct-sun range, year curve, today's solid path
- Moon gray `#CAC2D1`: shutter-button center fill, via-horizon inferred dashed segments, secondary emphasis (gap source, aiming status)
- Smoke gray `#8E8E93`: secondary text, unselected date pills, axes, horizon and plumb lines
- Strokes `#2E2E32` / `#3A3A3E`: card and button outlines
- Trajectory colors (all dashed): winter-solstice blue `#378ADD`, equinox red `#E24B4A`, summer-solstice orange `#EF9F27`
- Heading `+180°` pill: filled by default (offset applied), tap to toggle; placed next to the azimuth readout
- Point connections: white solid (measured) + moon-gray dashed (via-horizon inferred), shown by default
- "Lines" toggles: sun-path arcs and point connections on by default; horizon and plumb lines off by default

The capture screen has only shutter, done and delete controls: photos are always saved, no toggle; single shots only, no burst mode.

On black background vs harsh outdoor light, honestly: pure black is not the brightest option at noon, and black-on-white wins on raw ambient contrast at equal brightness. Black is kept for three reasons: the viewfinder itself is a bright scene (sky/facades), so a black frame contains the light and keeps the eye inside the picture while a white frame glares; on OLED black pixels emit nothing, saving power and heat during long field sessions; and it matches the icon (black with white crosshair). The cost is that secondary gray text goes first in sunlight, so all field-critical information (elevation, azimuth, hints, shutter) uses large white type and saturated trajectory colors, with gray reserved for post-check data like GPS and tick labels. If field tests still show washout, follow system brightness and thicker strokes first rather than flipping to a white theme.

## Technical notes (for developers)

- Sun position uses the simplified NOAA astronomical formulas. Cross-checked against a high-precision ephemeris the error stays below 0.02 degrees, far below sensor error, so the algorithm is never the bottleneck.
- Attitude comes from the Android rotation vector sensor, a fusion of accelerometer, gyroscope and magnetometer. Elevation references gravity and bypasses the compass, so the most decisive quantity is also the most stable one. Azimuth is taken along the rear-camera sight axis (about +180° from the body reading when held upright, applied by default and user-toggleable via `+180°`), then corrected to true north with magnetic declination.
- Magnetic declination comes straight from Android's GeomagneticField.getDeclination(), fed with the GPS latitude/longitude (plus altitude and time for extra accuracy) to yield true north. The API embeds the World Magnetic Model, so no bundled coefficient tables and no network are needed. On-site figure-eight calibration is still required, because declination only fixes the systematic offset, not local disturbance from balcony rebar.
- Each group stores two ordered point lists: ext[{az,el,gapAfter}] and ceil[{az,el}] (no gapAfter, ordering independent per zone). A long-press gap expands into three inferred segments: vertical drop from the previous point to the horizon, along-horizon run to the new point's direction, vertical rise to the new point. Evaluation interpolates measured segments by azimuth, takes the horizon on inferred segments, and takes the maximum elevation where azimuths overlap (occlusion wins), so reversed direction or occasional backtracking cannot corrupt the result. Photos carry the data in EXIF. Per-minute checks: external-only mode wants the sun above the external skyline (uncaptured azimuths count as open); external-plus-ceiling additionally wants the sun below the ceiling head (uncaptured azimuths count as wall); ceiling-only runs just the latter. A zone with an empty point list always passes. The engine samples the sun once per minute and accumulates visible minutes into durations. The capture screen's delete key long-press removes the point nearest to the crosshair on its right plus its connecting segment, inferred triple included, at most 1 point per hold and never anything on the left; the results screen's point list can delete any point and switch the segment between a point and its neighbor to the right (the previous shot) between "direct" and "via horizon" modes.
- The solstice reference arcs in the viewfinder are drawn with a planar azimuth-and-elevation to pixel projection, accurate within about 40 degrees of the view center, with near-zenith segments clipped automatically. The arcs depend only on latitude and date, so their samples are cached per declination inside the overlay and invalidated on location change; the phone orientation decides only which segment lands on screen and merely re-projects them. Sampling uses the geometric (refraction-free) elevation, consistent with the skyline evaluation.

## Status

All four phases (algorithm prototype, capture app, computation and visualization, polish and release) are implemented, with unit tests for the algorithms and instrumented tests for the UI. See [README.md](README.md) for the Chinese original.
