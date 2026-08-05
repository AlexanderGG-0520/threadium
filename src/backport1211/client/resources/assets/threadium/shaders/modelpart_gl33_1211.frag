#version 330 core
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform vec4 uColorModulator;
uniform vec4 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform float uGlintAlpha;
uniform int uShaderMode;
uniform int uAlphaCutout;

in float vDistance;
in vec2 vUv;
in vec4 vVertexColor;
flat in int vLight;
flat in int vOverlay;
out vec4 outColor;

vec4 linearFog(vec4 color) {
    if (vDistance <= uFogStart) return color;
    float fogValue = vDistance < uFogEnd ? smoothstep(uFogStart, uFogEnd, vDistance) : 1.0;
    return vec4(mix(color.rgb, uFogColor.rgb, fogValue * uFogColor.a), color.a);
}

float fogFade() {
    if (vDistance <= uFogStart) return 1.0;
    if (vDistance >= uFogEnd) return 0.0;
    return smoothstep(uFogEnd, uFogStart, vDistance);
}

void main() {
    if (uShaderMode == 9) {
        outColor = uColorModulator;
        return;
    }

    vec4 color = texture(Sampler0, vUv);
    if (uShaderMode == 6) {
        if (color.a == 0.0) discard;
        outColor = vec4(uColorModulator.rgb * vVertexColor.rgb, uColorModulator.a);
        return;
    }

    if (uShaderMode == 7) {
        color *= uColorModulator;
        if (color.a < 0.1) discard;
        float fade = fogFade() * uGlintAlpha;
        outColor = vec4(color.rgb * fade, color.a);
        return;
    }

    if (uShaderMode == 8) {
        if (color.a < 0.1) discard;
        outColor = color * uColorModulator;
        return;
    }

    if (uAlphaCutout != 0 && uShaderMode != 1 && color.a < 0.1) discard;

    if (uShaderMode == 10) {
        ivec2 overlay = ivec2(vOverlay & 65535, (vOverlay >> 16) & 65535);
        vec4 overlayColor = texelFetch(Sampler1, overlay, 0);
        color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
        color *= vVertexColor * uColorModulator;
    } else {
        color *= vVertexColor * uColorModulator;
        if (uAlphaCutout != 0 && uShaderMode == 1 && color.a < 0.1) discard;
        if (uShaderMode == 0 || uShaderMode == 2) {
            ivec2 overlay = ivec2(vOverlay & 65535, (vOverlay >> 16) & 65535);
            vec4 overlayColor = texelFetch(Sampler1, overlay, 0);
            color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
        }
    }
    if (uShaderMode == 0 || uShaderMode == 1 || uShaderMode == 3 || uShaderMode == 10) {
        ivec2 light = ivec2(vLight & 65535, (vLight >> 16) & 65535) / 16;
        color *= texelFetch(Sampler2, light, 0);
    }

    bool fadeOnly = uShaderMode == 2 || uShaderMode == 3 || uShaderMode == 4 || uShaderMode == 5;
    outColor = fadeOnly ? color * fogFade() : linearFog(color);
}
