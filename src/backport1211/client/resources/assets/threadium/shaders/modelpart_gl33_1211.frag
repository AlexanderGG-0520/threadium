#version 330 core
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
in vec2 vUv;
flat in int vLight;
flat in int vOverlay;
in vec4 vTint;
out vec4 outColor;
void main() {
    vec4 color = texture(Sampler0, vUv) * vTint;
    if (color.a < 0.1) discard;
    ivec2 light = ivec2(vLight & 65535, (vLight >> 16) & 65535) / 16;
    ivec2 overlay = ivec2(vOverlay & 65535, (vOverlay >> 16) & 65535) / 16;
    vec4 overlayColor = texelFetch(Sampler1, overlay, 0);
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    outColor = color * texelFetch(Sampler2, light, 0);
}
