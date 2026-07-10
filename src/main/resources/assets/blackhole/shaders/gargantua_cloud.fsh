#version 330

in vec4 vertexColor;
out vec4 fragColor;

layout(std140) uniform GargantuaParams {
    vec4 Material0; // time, density, filaments, grain
    vec4 Material1; // warp, flow, doppler, exposure
    vec4 Material2; // opening, divider, roll, brightness
    vec4 Material3; // blaze, bloom, opacity, reserved
};

const float TAU = 6.283185307179586;

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash13(i + vec3(0, 0, 0)), hash13(i + vec3(1, 0, 0)), f.x),
            mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y),
        mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x),
            mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y),
        f.z
    );
}

float fbm(vec3 p) {
    float value = 0.0;
    float amplitude = 0.52;
    mat3 basis = mat3(
         0.00,  0.80,  0.60,
        -0.80,  0.36, -0.48,
        -0.60, -0.48,  0.64
    );
    for (int i = 0; i < 5; ++i) {
        value += amplitude * noise3(p);
        p = basis * p * 2.03 + vec3(7.1, 3.7, 5.3);
        amplitude *= 0.49;
    }
    return value;
}

vec3 ringCoord(float angle, float radius, float angularScale, float radialScale) {
    return vec3(cos(angle) * angularScale, sin(angle) * angularScale,
                (radius - 1.0) * radialScale);
}

float cloudDensity(float radius, float angle, float time) {
    float densityControl = Material0.y;
    float filamentControl = Material0.z;
    float grainControl = Material0.w;
    float warpControl = Material1.x;
    float flowControl = Material1.y;

    float kepler = time * flowControl / pow(max(radius, 0.72), 1.5);
    float a0 = angle - kepler;
    vec3 warpCoord = ringCoord(a0, radius, 2.6, 9.0) + vec3(0.0, 0.0, time * 0.018);
    float warpA = fbm(warpCoord * 0.72);
    float warpB = fbm(warpCoord * 0.91 + vec3(11.3, 2.1, 7.7));
    float warpedAngle = a0 + (warpA - 0.5) * warpControl * 0.72;
    float warpedRadius = radius + (warpB - 0.5) * warpControl * 0.16;

    float broad = fbm(ringCoord(warpedAngle, warpedRadius, 3.6, 11.0)
                      + vec3(2.0, 5.0, time * 0.026));
    float a1 = angle - time * flowControl * 1.31 / pow(max(radius, 0.72), 1.5);
    float threadA = fbm(ringCoord(a1 + (warpA - 0.5) * 0.22, warpedRadius, 7.5, 62.0)
                        + vec3(13.0, 1.0, time * 0.014));
    float threadB = fbm(ringCoord(a1 * 0.997 - (warpB - 0.5) * 0.17,
                                  warpedRadius, 11.0, 112.0)
                        + vec3(1.0, 17.0, -time * 0.009));
    float a2 = angle - time * flowControl * 0.77 / pow(max(radius, 0.72), 1.5);
    float grains = noise3(ringCoord(a2, warpedRadius, 42.0, 176.0)
                          + vec3(0.0, 0.0, time * 0.11));

    float broadCloud = smoothstep(0.39, 0.76, broad);
    float fineThreads = pow(smoothstep(0.43, 0.75, threadA), 1.55);
    fineThreads *= 0.62 + 0.72 * pow(smoothstep(0.48, 0.78, threadB), 1.25);
    float dust = smoothstep(0.59, 0.90, grains) * (0.34 + fineThreads);
    float inner = smoothstep(1.015, 1.095, radius);
    float outer = 1.0 - smoothstep(2.62, 3.24, radius);
    float radialVeins = 0.72 + 0.28 * sin((warpedRadius - 1.0) * 94.0 + warpA * 8.0);
    radialVeins = mix(1.0, radialVeins, filamentControl);

    float density = broadCloud * 0.54;
    density += fineThreads * mix(0.35, 1.05, filamentControl);
    density += dust * mix(0.08, 0.66, grainControl);
    density *= radialVeins * inner * outer * densityControl;
    density *= mix(0.62, 1.0, 1.0 - smoothstep(1.05, 3.2, radius));
    return max(density, 0.0);
}

vec3 cloudEmission(float radius, float angle, float density) {
    float heat = 1.0 - smoothstep(1.08, 2.92, radius);
    vec3 darkAmber = vec3(0.22, 0.026, 0.004);
    vec3 amber = vec3(1.02, 0.24, 0.028);
    vec3 gold = vec3(1.48, 0.72, 0.22);
    vec3 whiteHot = vec3(1.92, 1.48, 1.26);
    vec3 color = mix(darkAmber, amber, smoothstep(0.0, 0.48, heat));
    color = mix(color, gold, smoothstep(0.40, 0.77, heat));
    color = mix(color, whiteHot, smoothstep(0.76, 1.0, heat));

    float approaching = -cos(angle);
    float beam = clamp(exp(approaching * Material1.z * 1.26), 0.25, 3.2);
    vec3 shifted = mix(color * vec3(1.0, 0.62, 0.44),
                       color * vec3(1.18, 1.10, 1.26),
                       smoothstep(-0.2, 1.0, approaching));
    float emission = density * beam * (0.74 + heat * 2.8);
    emission += pow(max(density - 0.42, 0.0), 2.2) * (2.4 + heat * 4.2);
    return shifted * emission;
}

void main() {
    vec2 p = (vertexColor.rg * 2.0 - 1.0) * 4.10;
    float cr = cos(Material2.z);
    float sr = sin(Material2.z);
    p = mat2(cr, -sr, sr, cr) * p;

    float opening = max(0.028, Material2.x);
    float divider = Material2.y;
    vec2 diskP = vec2(p.x, (p.y + divider) / opening);
    float diskRadius = length(diskP);
    float diskAngle = atan(diskP.y, diskP.x);
    float density = cloudDensity(diskRadius, diskAngle, Material0.x);
    vec3 directDisk = cloudEmission(diskRadius, diskAngle, density);

    float screenRadius = length(p);
    const float shadowRadius = 0.77;
    float outsideShadow = smoothstep(shadowRadius, shadowRadius + 0.024, screenRadius);
    directDisk *= outsideShadow;

    float lensT = clamp((screenRadius - 0.80) / 0.76, 0.0, 1.0);
    float lensRadius = mix(1.04, 2.86, lensT);
    float lensAngle = atan(p.y, p.x) + 0.07 * sin(Material0.x * 0.08);
    float lensDensity = cloudDensity(lensRadius, lensAngle, Material0.x * 0.83);
    float lensBand = smoothstep(0.78, 0.84, screenRadius)
                   * (1.0 - smoothstep(1.42, 1.64, screenRadius));
    float polarLift = 0.34 + 0.92 * pow(abs(p.y) / max(screenRadius, 0.001), 0.72);
    vec3 lensed = cloudEmission(lensRadius, lensAngle, lensDensity)
                * lensBand * polarLift * 0.58;

    float photon = exp(-abs(screenRadius - shadowRadius) * 176.0);
    float secondPhoton = exp(-abs(screenRadius - (shadowRadius + 0.105)) * 350.0);
    vec3 photonGlow = vec3(1.55, 0.96, 0.55) * photon * 1.45
                    + vec3(1.0, 0.76, 0.54) * secondPhoton * 0.36;

    // A controlled, textureless bloom approximation. It preserves the WebGL
    // preset's glow hierarchy without requiring a full-screen post chain.
    float broadDiskGlow = exp(-abs(diskRadius - 1.72) * 1.35)
                        * smoothstep(0.92, 1.05, diskRadius)
                        * (1.0 - smoothstep(2.75, 3.35, diskRadius));
    float haloGlow = exp(-abs(screenRadius - 0.92) * 5.0) * 0.18;
    vec3 bloom = vec3(1.0, 0.40, 0.10) * broadDiskGlow * (0.05 + density * 0.22)
               + vec3(0.38, 0.30, 0.62) * haloGlow;
    bloom *= Material3.y;

    // During the two edge-on acts the luminous divider must visibly cut the
    // shadow off-centre. This is what makes the upper/lower lobes unequal.
    float edgeOn = 1.0 - smoothstep(0.07, 0.24, opening);
    float dividerWidth = mix(0.018, 0.042, smoothstep(0.0, 0.08, opening));
    float dividerLine = exp(-abs(p.y + divider) / dividerWidth) * edgeOn;
    dividerLine *= 0.62 + 0.38 * noise3(vec3(p.x * 18.0, Material0.x * 0.32, 9.0));
    dividerLine *= 1.0 - smoothstep(shadowRadius - 0.01, shadowRadius + 0.03, screenRadius);
    vec3 crossing = vec3(1.35, 0.72, 0.25) * dividerLine * 1.35;

    float blazeMask = Material3.x * exp(-length(p - vec2(-0.92, 0.0)) * 1.25);
    vec3 blaze = vec3(1.0, 0.74, 0.48) * blazeMask * 0.55;

    vec3 color = (directDisk + lensed + photonGlow + bloom + crossing + blaze) * Material2.w;
    float alpha = clamp(density * 0.72 + lensDensity * lensBand * 0.54
                      + photon + secondPhoton + broadDiskGlow * 0.10
                      + dividerLine + blazeMask * 0.20, 0.0, 1.0);

    if (screenRadius < shadowRadius - 0.004) {
        color = crossing + blaze * 0.22;
        alpha = max(1.0, dividerLine);
    }

    // Filmic exposure compressed into the standard Minecraft color target.
    color = vec3(1.0) - exp(-max(color, vec3(0.0)) * Material1.w);
    alpha *= Material3.z;
    if (alpha < 0.002) {
        discard;
    }
    fragColor = vec4(color, alpha);
}

