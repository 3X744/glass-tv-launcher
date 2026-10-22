import * as THREE from "three";
import {
  DEFAULT_WEATHER_PRESET_ID,
  getWeatherPreset,
  type WeatherPreset,
  type WeatherPresetId,
} from "./weather";

const vertexShader = /* glsl */ `
  varying vec2 vUv;

  void main() {
    vUv = uv;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }
`;

const fragmentShader = /* glsl */ `
  precision highp float;

  uniform sampler2D uScene;
  uniform float uTime;
  uniform vec3 uTint;
  uniform float uBrightness;
  uniform float uContrast;
  uniform float uSaturation;
  uniform float uVignette;
  uniform float uCloudStrength;
  uniform vec3 uLightColor;
  uniform float uLightStrength;
  uniform vec2 uLightDirection;
  uniform vec3 uHazeColor;
  uniform float uHaze;
  uniform vec3 uGradeColor;
  uniform float uGrade;
  varying vec2 vUv;

  void main() {
    vec3 color = texture2D(uScene, vUv).rgb;

    float cloudWave =
      sin(vUv.x * 5.2 + vUv.y * 2.1 + uTime * 0.035)
      + sin(vUv.x * 2.3 - vUv.y * 4.7 - uTime * 0.022)
      + sin((vUv.x + vUv.y) * 8.1 + uTime * 0.016);
    cloudWave = cloudWave / 6.0 + 0.5;
    color *= 1.0 - uCloudStrength * (0.055 + cloudWave * 0.075);

    color *= uTint * uBrightness;
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luminance), color, uSaturation);
    // Colour grade: pull toward the scene's luminance in one hue (amber dusk, blue night).
    // A plain tint can't do this: amber times green water is still green.
    color = mix(color, luminance * uGradeColor, uGrade);
    color = (color - 0.5) * uContrast + 0.5;

    vec2 centered = vUv - 0.5;
    float directionalLight = dot(centered, normalize(uLightDirection)) + 0.5;
    directionalLight = smoothstep(0.05, 0.95, directionalLight);
    color += uLightColor * directionalLight * uLightStrength;

    float edge = smoothstep(0.36, 0.76, length(centered * vec2(1.0, 1.3)));
    color *= 1.0 - edge * uVignette;

    // Drifting haze (mist): thicker in the cloud bands, so it reads as fog over the water
    color = mix(color, uHazeColor, uHaze * (0.7 + cloudWave * 0.6));
    gl_FragColor = vec4(max(color, vec3(0.0)), 1.0);
  }
`;

const scalarProperties = [
  "brightness",
  "contrast",
  "saturation",
  "vignette",
  "cloudStrength",
  "lightStrength",
  "haze",
  "grade",
] as const;

interface WeatherState {
  tint: THREE.Color;
  lightColor: THREE.Color;
  lightDirection: THREE.Vector2;
  brightness: number;
  contrast: number;
  saturation: number;
  vignette: number;
  cloudStrength: number;
  lightStrength: number;
  hazeColor: THREE.Color;
  haze: number;
  gradeColor: THREE.Color;
  grade: number;
}

function stateFromPreset(preset: WeatherPreset): WeatherState {
  return {
    tint: new THREE.Color().setRGB(...preset.tint),
    lightColor: new THREE.Color().setRGB(...preset.lightColor),
    lightDirection: new THREE.Vector2(...preset.lightDirection),
    brightness: preset.brightness,
    contrast: preset.contrast,
    saturation: preset.saturation,
    vignette: preset.vignette,
    cloudStrength: preset.cloudStrength,
    lightStrength: preset.lightStrength,
    hazeColor: new THREE.Color().setRGB(...(preset.hazeColor ?? [1, 1, 1])),
    haze: preset.haze ?? 0,
    gradeColor: new THREE.Color().setRGB(...(preset.gradeColor ?? [1, 1, 1])),
    grade: preset.grade ?? 0,
  };
}

export class WeatherPass {
  public readonly mesh: THREE.Mesh<THREE.PlaneGeometry, THREE.ShaderMaterial>;

  private readonly material: THREE.ShaderMaterial;
  private readonly current = stateFromPreset(
    getWeatherPreset(DEFAULT_WEATHER_PRESET_ID),
  );
  private target = stateFromPreset(getWeatherPreset(DEFAULT_WEATHER_PRESET_ID));
  private previousTime = -1;

  public constructor(sceneTexture: THREE.Texture) {
    this.material = new THREE.ShaderMaterial({
      uniforms: {
        uScene: { value: sceneTexture },
        uTime: { value: 0 },
        uTint: { value: this.current.tint },
        uBrightness: { value: this.current.brightness },
        uContrast: { value: this.current.contrast },
        uSaturation: { value: this.current.saturation },
        uVignette: { value: this.current.vignette },
        uCloudStrength: { value: this.current.cloudStrength },
        uLightColor: { value: this.current.lightColor },
        uLightStrength: { value: this.current.lightStrength },
        uLightDirection: { value: this.current.lightDirection },
        uHazeColor: { value: this.current.hazeColor },
        uHaze: { value: this.current.haze },
        uGradeColor: { value: this.current.gradeColor },
        uGrade: { value: this.current.grade },
      },
      vertexShader,
      fragmentShader,
      depthTest: false,
      depthWrite: false,
      toneMapped: false,
    });
    this.mesh = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), this.material);
    this.mesh.frustumCulled = false;
  }

  /** `immediate` starts on the preset without the fade (the TV screensaver opens straight on its sky). */
  public setPreset(id: WeatherPresetId, immediate = false): void {
    this.target = stateFromPreset(getWeatherPreset(id));
    if (!immediate) return;
    // Copy in place: the uniforms hold the current colour and vector objects
    this.current.tint.copy(this.target.tint);
    this.current.lightColor.copy(this.target.lightColor);
    this.current.lightDirection.copy(this.target.lightDirection);
    this.current.hazeColor.copy(this.target.hazeColor);
    this.current.gradeColor.copy(this.target.gradeColor);
    for (const property of scalarProperties) {
      this.current[property] = this.target[property];
    }
  }

  public update(time: number): void {
    const deltaTime =
      this.previousTime < 0 ? 0 : Math.min(0.1, time - this.previousTime);
    this.previousTime = time;
    const blend = 1 - Math.exp(-deltaTime * 2.25);

    this.current.tint.lerp(this.target.tint, blend);
    this.current.lightColor.lerp(this.target.lightColor, blend);
    this.current.lightDirection.lerp(this.target.lightDirection, blend);
    this.current.hazeColor.lerp(this.target.hazeColor, blend);
    this.current.gradeColor.lerp(this.target.gradeColor, blend);
    for (const property of scalarProperties) {
      this.current[property] +=
        (this.target[property] - this.current[property]) * blend;
    }

    this.material.uniforms.uTime.value = time;
    this.material.uniforms.uBrightness.value = this.current.brightness;
    this.material.uniforms.uContrast.value = this.current.contrast;
    this.material.uniforms.uSaturation.value = this.current.saturation;
    this.material.uniforms.uVignette.value = this.current.vignette;
    this.material.uniforms.uCloudStrength.value = this.current.cloudStrength;
    this.material.uniforms.uLightStrength.value = this.current.lightStrength;
    this.material.uniforms.uHaze.value = this.current.haze;
    this.material.uniforms.uGrade.value = this.current.grade;
  }

  public dispose(): void {
    this.mesh.geometry.dispose();
    this.material.dispose();
  }
}
