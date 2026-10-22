# Glass changes to nagomi

Vendored from [msk1039/nagomi](https://github.com/msk1039/nagomi) at commit `01e93a4` (2026-09-29), PolyForm
Noncommercial 1.0.0 (see `LICENSE`; non-commercial use only). Used as Glass's Fire TV
screensaver. Built with `..\build.ps1` into `glass-launcher/launcher/app/src/main/assets/nagomi/`.

All changes are behind the `?tv=1` URL flag; the web app behaves as before.

- `src/main.tsx` is now a dispatcher: `?tv=1` loads `tv-main.ts`, anything else `web-main.tsx` (the old entry).
  It also adds a JSON `structuredClone` fallback for WebViews older than Chrome 98.
- `src/tv.ts`: URL options (`seed`, `weather`, `rain`, `giant`) and `tvRandom(salt)` seeded generators.
- `src/tv-main.ts`: UI-free pond (no React, sound, analytics or saved settings), 30 fps, and
  `window.glassTv.setWeather(id, rain)` (fade through black) for live sky changes from Android.
- `src/tv-randomize.ts`: random koi count, sizes, tiny-fish schools, lotus leaves/flowers and duckweed per seed;
  larger tiny fish and butterflies and longer, wider rain rings for viewing from across a room.
  Collections are written field by field (the store only validates leaf values).
- `src/fish-appearance.ts`: random variety per fish with a slight colour shift; the giant is gold (kin showa) or
  platinum (hariwake) with its own saddles and sumi spots (`GIANT_PATCHES`).
- `src/weather.ts`, `src/weather-pass.ts`: two optional preset terms, `haze`/`hazeColor` (drifting fog, used by mist
  and lightly by overcast) and `grade`/`gradeColor` (a one-hue luminance grade: amber sunset, blue moonlight). Sunset,
  overcast, mist, deep-clear and moonlight retuned so each reads differently at a glance; `setPreset(id, immediate)`
  lets the screensaver open straight on its sky instead of fading in from sunny.
- `src/koi.ts`: fish 0 becomes the giant koi (about 2.6x length, slower, wider turns) when `giant=1`.
- `src/school.ts`: seeded from the session seed instead of the fixed `0x00c0ffee`.
