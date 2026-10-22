// The pond as Glass's Fire TV screensaver: no UI, no sound, no saved settings.
// A fresh random pond each start (tv-randomize.ts), the sky picked by Android
// from the real time and weather, and live sky changes through window.glassTv.
import { CANVAS, FIXED_STEP, setCanvasSize } from "./config";
import { FishRenderer } from "./fish-renderer";
import { createFrameLimiter } from "./frame-limiter";
import { School } from "./school";
import { connectSettingsEffects } from "./settings/effects";
import { settings } from "./settings/store";
import { TV } from "./tv";
import { randomizePond } from "./tv-randomize";
import { WEATHER_PRESETS, type WeatherPresetId } from "./weather";

/** The stick's GPU is modest: 30 fps looks the same for slow koi and halves the work. */
const FPS = 30;
const BUTTERFLY_PRESETS: readonly WeatherPresetId[] = ["sunny", "deep-clear", "overcast", "sunset"];

const isPreset = (id: string | null): id is WeatherPresetId =>
  id !== null && WEATHER_PRESETS.some((preset) => preset.id === id);

const style = document.createElement("style");
style.textContent = `
  html, body { margin: 0; height: 100%; background: #000; overflow: hidden; cursor: none; }
  canvas { display: block; width: 100vw; height: 100vh; image-rendering: pixelated;
           transition: opacity 1.4s ease; }`;
document.head.append(style);
const canvas = document.createElement("canvas");
(document.querySelector("#root") ?? document.body).append(canvas);

randomizePond(settings);

function applySky(id: WeatherPresetId, rain: boolean): void {
  settings.setWeather(id);
  settings.setRain(rain);
  // Butterflies only on dry, light skies
  settings.set(["butterflies", "visibleCount"], BUTTERFLY_PRESETS.includes(id) && !rain ? 4 : 0);
}
applySky(isPreset(TV.weather) ? TV.weather : "sunny", TV.rain);

setCanvasSize(CANVAS.width, CANVAS.height);
const school = new School();
const renderer = new FishRenderer(canvas);
renderer.setWeatherPreset(settings.meta().weather, true);
school.setRainIntensity(settings.meta().rain ? 1 : 0);
connectSettingsEffects(settings, { school, renderer });

declare global {
  interface Window {
    glassTv?: { setWeather(id: string, rain: boolean): void };
  }
}

// Called by ScreensaverActivity when the real sky changes: fade through black, switch, fade back
window.glassTv = {
  setWeather(id, rain) {
    if (!isPreset(id)) return;
    if (id === settings.meta().weather && rain === settings.meta().rain) return;
    canvas.style.opacity = "0";
    window.setTimeout(() => {
      applySky(id, rain);
      renderer.setWeatherPreset(id, true);
      school.setRainIntensity(rain ? 1 : 0);
      canvas.style.opacity = "1";
    }, 1400);
  },
};

let accumulator = 0;
let simulationTime = 0;
let previousTime = performance.now();
const frameLimiter = createFrameLimiter();
const animate = (now: number): void => {
  requestAnimationFrame(animate);
  if (!frameLimiter.shouldRender(now, FPS)) return;
  accumulator += Math.min((now - previousTime) / 1000, 0.1);
  previousTime = now;
  while (accumulator >= FIXED_STEP) {
    simulationTime += FIXED_STEP;
    school.update(FIXED_STEP, simulationTime);
    accumulator -= FIXED_STEP;
  }
  renderer.draw(school, simulationTime, false);
};
requestAnimationFrame(animate);
