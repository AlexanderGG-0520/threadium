#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

#ifndef THREADIUM_WATER_MASK
uniform sampler2D Sampler0;
#endif
#if defined(THREADIUM_LIT_OVERLAY) || defined(THREADIUM_EMISSIVE_OVERLAY) || defined(THREADIUM_DISSOLVE)
uniform sampler2D Sampler1;
#endif
#if defined(THREADIUM_LIT_OVERLAY) || defined(THREADIUM_LIT_NO_OVERLAY) || defined(THREADIUM_LIT_TEXTURE_MATRIX) || defined(THREADIUM_DISSOLVE)
uniform sampler2D Sampler2;
#endif
#ifdef THREADIUM_DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
flat in int vertexLight;
flat in int vertexOverlay;
out vec4 fragColor;

void main() {
#ifdef THREADIUM_WATER_MASK
    fragColor = vec4(0.0);
#else
    vec4 color = texture(Sampler0, texCoord0);
#ifdef THREADIUM_OUTLINE
    if (color.a == 0.0) discard;
    fragColor = vec4(ColorModulator.rgb * vertexColor.rgb, ColorModulator.a);
    return;
#endif
#ifdef THREADIUM_ALPHA_CUTOUT
    if (color.a < 0.1) discard;
#endif
#ifdef THREADIUM_PER_FACE
    vec4 shadedTint = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    vec4 shadedTint = vertexColor;
#endif
#ifdef THREADIUM_DISSOLVE
    if (shadedTint.a < texture(DissolveMaskSampler, texCoord0).a) discard;
    shadedTint.a = 1.0;
#endif
    color *= shadedTint * ColorModulator;
#if defined(THREADIUM_LIT_OVERLAY) || defined(THREADIUM_EMISSIVE_OVERLAY) || defined(THREADIUM_DISSOLVE)
    ivec2 overlayCoords = ivec2(vertexOverlay & 65535, (vertexOverlay >> 16) & 65535);
    vec4 overlayColor = texelFetch(Sampler1, overlayCoords, 0);
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#if defined(THREADIUM_LIT_OVERLAY) || defined(THREADIUM_LIT_NO_OVERLAY) || defined(THREADIUM_LIT_TEXTURE_MATRIX) || defined(THREADIUM_DISSOLVE)
    ivec2 lightCoords = ivec2(vertexLight & 65535, (vertexLight >> 16) & 65535) / 16;
    color *= texelFetch(Sampler2, lightCoords, 0);
#endif
#ifdef THREADIUM_GLINT
    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    color = vec4(color.rgb * fade, color.a);
#else
    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
    fragColor = color;
#endif
}
