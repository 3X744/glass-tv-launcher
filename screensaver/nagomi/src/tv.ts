// Options for the Glass Fire TV screensaver, read once from the URL that
// ScreensaverActivity builds: ?tv=1&seed=…&weather=…&rain=0|1&giant=0|1
import { XorShift32 } from "./math";

const params = new URLSearchParams(window.location.search);

export const TV = {
  active: params.has("tv"),
  /** Seeds every random choice of this pond, so one session is self-consistent. */
  seed: (Number(params.get("seed")) || Math.floor(Math.random() * 0x7fffffff) + 1) >>> 0,
  /** The rare giant koi (fish 0). */
  giant: params.get("giant") === "1",
  weather: params.get("weather"),
  rain: params.get("rain") === "1",
};

/** A generator for one kind of choice; different salts give independent streams. */
export function tvRandom(salt: number): XorShift32 {
  const random = new XorShift32((TV.seed ^ Math.imul(salt + 1, 0x9e3779b9)) >>> 0 || 1);
  random.next();
  random.next();
  return random;
}
