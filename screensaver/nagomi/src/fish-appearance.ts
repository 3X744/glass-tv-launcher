import * as THREE from "three";
import {
  FISH,
  KOI_PALETTES,
  KOI_PATTERN_PATCHES,
  type KoiPatchSetting,
} from "./config";
import { TV, tvRandom } from "./tv";

export enum KoiPattern {
  Kohaku,
  Sanke,
  Showa,
  Ogon,
  Tancho,
  Shiro,
}

export interface FishAppearance {
  pattern: KoiPattern;
  base: THREE.Color;
  accent: THREE.Color;
  marking: THREE.Color;
  fin: THREE.Color;
  eye: THREE.Color;
  /** The giant koi: drawn with GIANT_PATCHES instead of its family's markings. */
  giant: boolean;
}

/** The giant koi's coats: gold (kin showa: deep orange saddles, sumi) or platinum (hariwake: gold saddles, sumi). */
const GOLD = { base: 0xeab23e, accent: 0xb9461a, marking: 0x1f1a17, fin: 0xf3d48a };
const PLATINUM = { base: 0xe2ddd0, accent: 0xd99a2b, marking: 0x26241f, fin: 0xf6f3ea };

/** Saddles along the back plus sumi spots, so the giant reads as a koi and not a plain blob at TV distance. */
const GIANT_PATCHES: readonly KoiPatchSetting[] = [
  { position: 0.13, length: 0.075, width: 0.5, offset: 0, color: "accent" },
  { position: 0.36, length: 0.12, width: 0.78, offset: 0.06, color: "accent" },
  { position: 0.62, length: 0.11, width: 0.7, offset: -0.1, color: "accent" },
  { position: 0.25, length: 0.065, width: 0.42, offset: -0.38, color: "marking" },
  { position: 0.49, length: 0.075, width: 0.46, offset: 0.36, color: "marking" },
  { position: 0.75, length: 0.065, width: 0.4, offset: -0.28, color: "marking" },
];

export const createFishAppearance = (index: number): FishAppearance => {
  let pattern = index % KOI_PALETTES.length;
  // The Fire TV screensaver deals each fish a random variety with a slight colour shift.
  // Seeded per index, so the renderer's two appearance sets for a fish always agree.
  const random = TV.active ? tvRandom(100 + index) : null;
  if (random) pattern = Math.floor(random.range(0, KOI_PALETTES.length));
  const giant = TV.active && TV.giant && index === 0;
  if (giant) pattern = KoiPattern.Ogon;
  const palette = giant ? (tvRandom(7).unit() < 0.5 ? PLATINUM : GOLD) : KOI_PALETTES[pattern];
  const appearance = {
    pattern,
    base: new THREE.Color(palette.base),
    accent: new THREE.Color(palette.accent),
    marking: new THREE.Color(palette.marking),
    fin: new THREE.Color(palette.fin),
    eye: new THREE.Color(FISH.eyeColor),
    giant,
  };
  if (random && !giant) {
    const hue = random.range(-0.02, 0.02), light = random.range(-0.04, 0.03);
    appearance.base.offsetHSL(hue, 0, light);
    appearance.accent.offsetHSL(hue, random.range(-0.05, 0.05), 0);
    appearance.fin.offsetHSL(hue, 0, light);
  }
  return appearance;
};

export const patchesFor = (
  appearance: FishAppearance,
): readonly KoiPatchSetting[] =>
  appearance.giant ? GIANT_PATCHES : KOI_PATTERN_PATCHES[appearance.pattern];
