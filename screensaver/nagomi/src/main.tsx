// Entry point. `?tv=1` (Glass's Fire TV screensaver) loads a lean, UI-free pond;
// everything else gets the full web app. Separate chunks, so neither loads the other.
if (new URLSearchParams(window.location.search).has("tv")) {
  // Older Android TV WebViews (before Chrome 98, e.g. the emulator's) lack structuredClone;
  // the settings store only clones plain data, so a JSON copy is enough there.
  if (typeof globalThis.structuredClone !== "function") {
    globalThis.structuredClone = <T,>(value: T): T =>
      value === undefined ? value : (JSON.parse(JSON.stringify(value)) as T);
  }
  void import("./tv-main");
} else {
  void import("./web-main");
}
