#version 330

uniform sampler2D Sampler0;

in vec4 vertexColor;
out vec4 fragColor;

layout(std140) uniform GargantuaParams {
    vec4 Material0; // time, density, filaments, grain
    vec4 Material1; // warp, flow, doppler, exposure
    vec4 Material2; // opening, divider, roll, brightness
    vec4 Material3; // blaze, bloom, opacity, reserved
};

const float TAU = 6.283185307179586;
const float SHADOW_RADIUS = 0.77;

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

float softMask(float value, float edge0, float edge1) {
    float width = max(fwidth(value) * 1.35, 0.0015);
    return smoothstep(edge0 - width, edge1 + width, value);
}

/**
 * Samples the pre-baked seamless cloud strip as a polar flow map. Three
 * advected layers retain the approved fine filaments and grains without the
 * old shader's twenty-five 3D-noise evaluations per cloud lookup.
 */
vec4 sampleFlow(float radius, float angle, float time, float phase) {
    float radial = (radius - 1.015) / 2.25;
    float inside = softMask(radius, 1.015, 1.075)
                 * (1.0 - softMask(radius, 2.83, 3.25));
    if (inside <= 0.0001) {
        return vec4(0.0);
    }

    float orbit = time * Material1.y * 0.052 / pow(max(radius, 0.78), 1.5);
    float warp = sin(angle * 3.0 + time * 0.11 + phase * TAU) * 0.020;
    warp += sin(angle * 7.0 - time * 0.073 - phase * 3.0) * 0.009;
    radial = clamp(radial + warp * Material1.x * (1.0 - radial), 0.002, 0.998);

    float u = angle / TAU - orbit + phase;
    vec3 broad = texture(Sampler0, vec2(fract(u), radial)).rgb;
    vec3 threads = texture(Sampler0, vec2(
            fract(u * 1.973 + phase * 0.71 + time * 0.006),
            clamp(radial * 0.965 + 0.018, 0.002, 0.998))).rgb;
    vec3 grains = texture(Sampler0, vec2(
            fract(u * 3.117 - phase * 0.43 - time * 0.011),
            clamp(radial * 1.075 - 0.024, 0.002, 0.998))).rgb;

    vec3 texel = broad * 0.55
               + threads * (0.25 + Material0.z * 0.10)
               + grains * (0.08 + Material0.w * 0.10);
    float density = luminance(texel);
    density = smoothstep(0.018, 0.72, density) * inside * Material0.y;

    // Slowly moving large-scale gaps prevent the baked strip reading as a
    // rigid stack of concentric hoops.
    float clumps = 0.78
                 + 0.14 * sin(angle * 2.0 - time * 0.085 + radius * 3.1)
                 + 0.08 * sin(angle * 5.0 + time * 0.047 - radius * 5.7);
    density *= max(clumps, 0.38);

    vec3 chroma = texel / max(luminance(texel), 0.035);
    return vec4(chroma, max(density, 0.0));
}

vec3 flowEmission(float radius, float angle, vec4 flow) {
    float heat = 1.0 - smoothstep(1.08, 2.92, radius);
    vec3 darkAmber = vec3(0.24, 0.028, 0.004);
    vec3 amber = vec3(1.08, 0.25, 0.025);
    vec3 gold = vec3(1.52, 0.76, 0.24);
    vec3 whiteHot = vec3(1.95, 1.56, 1.35);
    vec3 palette = mix(darkAmber, amber, smoothstep(0.0, 0.48, heat));
    palette = mix(palette, gold, smoothstep(0.40, 0.77, heat));
    palette = mix(palette, whiteHot, smoothstep(0.76, 1.0, heat));
    palette = mix(palette, flow.rgb * palette, 0.36);

    float approaching = -cos(angle);
    float beam = clamp(exp(approaching * Material1.z * 1.18), 0.32, 2.85);
    vec3 shifted = mix(palette * vec3(1.0, 0.64, 0.46),
                       palette * vec3(1.14, 1.08, 1.23),
                       smoothstep(-0.25, 1.0, approaching));
    float emission = flow.a * beam * (0.68 + heat * 2.65);
    emission += smoothstep(0.52, 1.08, flow.a) * (0.32 + heat * 1.7);
    return shifted * emission;
}

void main() {
    vec2 p = (vertexColor.rg * 2.0 - 1.0) * 4.10;
    float cr = cos(Material2.z);
    float sr = sin(Material2.z);
    p = mat2(cr, -sr, sr, cr) * p;

    float time = Material0.x;
    float opening = max(0.028, Material2.x);
    float divider = Material2.y;

    vec2 diskP = vec2(p.x, (p.y + divider) / opening);
    float diskRadius = length(diskP);
    float diskAngle = atan(diskP.y, diskP.x);
    vec4 directFlow = sampleFlow(diskRadius, diskAngle, time, 0.07);
    vec3 directDisk = flowEmission(diskRadius, diskAngle, directFlow);

    float screenRadius = length(p);
    float shadowAA = max(fwidth(screenRadius) * 1.5, 0.0025);
    float outsideShadow = smoothstep(SHADOW_RADIUS - shadowAA,
                                     SHADOW_RADIUS + shadowAA, screenRadius);
    directDisk *= outsideShadow;

    // Gravitationally lensed image: it remaps and resamples the same baked
    // flow field rather than rebuilding a second procedural cloud volume.
    float lensT = clamp((screenRadius - 0.80) / 0.76, 0.0, 1.0);
    float lensRadius = mix(1.04, 2.86, lensT);
    float lensAngle = atan(p.y, p.x) + 0.055 * sin(time * 0.075);
    vec4 lensFlow = sampleFlow(lensRadius, lensAngle, time * 0.86, 0.41);
    float lensBand = softMask(screenRadius, 0.775, 0.84)
                   * (1.0 - softMask(screenRadius, 1.42, 1.66));
    float polarLift = 0.34 + 0.92 * pow(abs(p.y) / max(screenRadius, 0.001), 0.72);
    vec3 lensed = flowEmission(lensRadius, lensAngle, lensFlow)
                * lensBand * polarLift * 0.62;

    float photonWidth = max(fwidth(screenRadius) * 1.8, 0.0030);
    float photon = 1.0 - smoothstep(photonWidth * 0.35, photonWidth * 2.7,
                                   abs(screenRadius - SHADOW_RADIUS));
    float secondPhoton = 1.0 - smoothstep(photonWidth * 0.30, photonWidth * 1.25,
                                         abs(screenRadius - (SHADOW_RADIUS + 0.105)));
    vec3 photonGlow = vec3(1.58, 1.02, 0.61) * photon * 1.42
                    + vec3(1.0, 0.76, 0.54) * secondPhoton * 0.34;

    // Cheap wide emission lobes provide a stable bloom silhouette. They do
    // not resample noise and therefore remain inexpensive at full-screen size.
    float broadDiskGlow = exp(-abs(diskRadius - 1.62) * 1.45)
                        * softMask(diskRadius, 0.94, 1.06)
                        * (1.0 - softMask(diskRadius, 2.72, 3.28));
    float haloGlow = exp(-abs(screenRadius - 0.91) * 5.2) * 0.17;
    vec3 bloom = vec3(1.0, 0.37, 0.075)
               * broadDiskGlow * (0.035 + directFlow.a * 0.20);
    bloom += vec3(0.34, 0.27, 0.58) * haloGlow;
    bloom *= Material3.y;

    // Edge-on acts need a textured luminous divider across the shadow. One
    // strip lookup supplies persistent grains without temporal noise shimmer.
    float edgeOn = 1.0 - smoothstep(0.07, 0.24, opening);
    float dividerWidth = mix(0.016, 0.039, smoothstep(0.0, 0.08, opening));
    dividerWidth = max(dividerWidth, fwidth(p.y) * 1.35);
    float dividerLine = exp(-abs(p.y + divider) / dividerWidth) * edgeOn;
    float dividerGrain = luminance(texture(Sampler0, vec2(
            fract(p.x * 0.085 - time * 0.018), 0.115)).rgb);
    dividerLine *= 0.58 + 0.62 * dividerGrain;
    dividerLine *= 1.0 - smoothstep(SHADOW_RADIUS - shadowAA,
                                    SHADOW_RADIUS + shadowAA * 2.0, screenRadius);
    vec3 crossing = vec3(1.45, 0.78, 0.27) * dividerLine * 1.48;

    float blazeMask = Material3.x * exp(-length(p - vec2(-0.92, 0.0)) * 1.30);
    vec3 blaze = vec3(1.0, 0.76, 0.50) * blazeMask * 0.52;

    vec3 color = directDisk + lensed + photonGlow + bloom + crossing + blaze;
    if (screenRadius < SHADOW_RADIUS - shadowAA) {
        color = crossing + blaze * 0.20;
    }

    color *= Material2.w * Material3.z;
    color = vec3(1.0) - exp(-max(color, vec3(0.0)) * Material1.w);
    if (max(color.r, max(color.g, color.b)) < 0.0015) {
        discard;
    }

    // The pipeline uses additive blending: emission is no longer multiplied
    // by cloud density a second time through conventional source alpha.
    fragColor = vec4(color, 1.0);
}
