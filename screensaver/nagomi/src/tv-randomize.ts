// A new pond for every screensaver start: koi count and sizes, tiny-fish
// schools, lotus leaves and flowers, and duckweed are drawn from the session
// seed and written through the settings store before the pond is built.
import { CANVAS } from "./config";
import type { XorShift32 } from "./math";
import type { SettingPath } from "./settings/schema";
import type { SettingsStore } from "./settings/store";
import { TV, tvRandom } from "./tv";

const int = (r: XorShift32, low: number, highExclusive: number): number =>
  Math.floor(r.range(low, highExclusive));
const round = (value: number): number => Number(value.toFixed(2));

/** A point near one of the four pond edges, where leaves and duckweed grow. */
function edgePosition(r: XorShift32): { x: number; y: number } {
  const w = CANVAS.width, h = CANVAS.height;
  switch (int(r, 0, 4)) {
    case 0: return { x: int(r, 16, w - 15), y: int(r, 8, 43) };
    case 1: return { x: int(r, w - 42, w - 7), y: int(r, 16, h - 15) };
    case 2: return { x: int(r, 16, w - 15), y: int(r, h - 42, h - 7) };
    default: return { x: int(r, 8, 43), y: int(r, 16, h - 15) };
  }
}

/**
 * The store validates leaf values only, so a collection is written field by field.
 * Set its visible count first: raising it grows the collection to fit.
 */
function setItems(settings: SettingsStore, path: SettingPath, items: readonly Record<string, number>[]): void {
  items.forEach((item, index) => {
    for (const [key, value] of Object.entries(item)) settings.set([...path, index, key], value);
  });
}

export function randomizePond(settings: SettingsStore): void {
  const koi = tvRandom(1);
  settings.set(["koi", "initialCount"], TV.giant ? int(koi, 4, 9) : int(koi, 5, 13));
  const shortest = round(koi.range(24, 31));
  settings.set(["koi", "regularLength"], [shortest, round(shortest + koi.range(9, 15))]);
  settings.set(["koi", "tinyEvery"], [2, 3, 4, 6][int(koi, 0, 4)]);

  const tiny = tvRandom(2);
  const schools = Array.from({ length: 4 }, () => ({
    x: int(tiny, 48, CANVAS.width - 47),
    y: int(tiny, 38, CANVAS.height - 37),
    count: int(tiny, 10, 30),
    heading: round(tiny.range(-Math.PI, Math.PI)),
    spreadX: int(tiny, 18, 41),
    spreadY: int(tiny, 9, 21),
    palette: int(tiny, 0, 4),
    // Larger than the web default: at 3 m from a TV the default schools read as specks
    sizeScale: round(tiny.range(1.15, 1.45)),
    speedScale: round(tiny.range(0.88, 1.14)),
    swirlDirection: tiny.unit() < 0.5 ? -1 : 1,
  }));
  settings.set(["tiny-fish", "visibleSchoolCount"], int(tiny, 1, 5));
  setItems(settings, ["tiny-fish-schools"], schools);

  const lotus = tvRandom(3);
  const leafCount = int(lotus, 9, 20);
  const leaves = Array.from({ length: leafCount }, () => ({
    ...edgePosition(lotus),
    radius: int(lotus, 12, 30),
    angle: round(lotus.range(0, Math.PI * 2)),
    phase: round(lotus.range(0, Math.PI * 2)),
    palette: int(lotus, 0, 2),
  }));
  settings.set(["lotus", "visibleLeafCount"], leafCount);
  setItems(settings, ["lotus-leaves"], leaves);
  const flowerCount = int(lotus, 0, 6);
  const flowers = Array.from({ length: Math.max(1, flowerCount) }, () => ({
    leafIndex: int(lotus, 0, leafCount),
    radius: round(lotus.range(4.2, 5.6)),
    offsetX: round(lotus.range(-1.5, 1.5)),
    offsetY: round(lotus.range(-1, 1)),
    rotation: round(lotus.range(0, Math.PI)),
    palette: int(lotus, 0, 2),
  }));
  settings.set(["lotus", "visibleFlowerCount"], flowerCount);
  setItems(settings, ["lotus-flowers"], flowers);

  const weed = tvRandom(4);
  const patchCount = int(weed, 3, 9);
  const patches = Array.from({ length: patchCount }, () => {
    const radius = int(weed, 18, 65);
    return {
      ...edgePosition(weed),
      radius,
      count: Math.round(radius * weed.range(1.8, 3)),
      phase: round(weed.range(0, Math.PI * 2)),
      palette: int(weed, 0, 2),
    };
  });
  settings.set(["duckweed", "visiblePatchCount"], patchCount);
  setItems(settings, ["duckweed-patches"], patches);

  // Sized for viewing from the sofa: bigger butterflies, and rain rings that last and spread far enough to see
  for (const [key, scale] of [["bodyLength", 1.5], ["bodyWidth", 1.5], ["headRadius", 1.5], ["wingLength", 1.5], ["wingWidth", 1.5], ["wingSpotRadius", 1.5]] as const) {
    settings.set(["butterflies", key], round(settings.get(["butterflies", key]) as number * scale));
  }
  const rain = ["ripples", "types", "rain"];
  settings.set([...rain, "lifetime"], 1.8);
  settings.set([...rain, "startRadius"], 2);
  settings.set([...rain, "expansionSpeed"], 40);
  settings.set([...rain, "distortion"], 9);
  // One ring per drop. The water shader loops over every live ring for every pixel, so the stick's GPU sets the
  // budget: ~25 rings (14 drops/s x 1.8 s) holds frame rate, where 32 drops/s (~58 rings) dropped it to ~12 fps
  settings.set([...rain, "ripplesPerEvent"], 1);
  settings.set([...rain, "maximumActive"], 24);
  settings.set(["ripples", "rainEmitter", "dropsPerSecond"], 14);
}

