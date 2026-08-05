#version 330 core
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
layout(location=3) in float aBone;
layout(location=4) in vec4 iRoot0;
layout(location=5) in vec4 iRoot1;
layout(location=6) in vec4 iRoot2;
layout(location=7) in vec4 iRoot3;
layout(location=8) in int iBoneBase;
layout(location=9) in int iLight;
layout(location=10) in int iOverlay;
layout(location=11) in vec4 iTint;
layout(location=12) in int iDecalBase;

uniform mat4 uProjection;
uniform mat4 uModelView;
uniform mat4 uTextureMatrix;
uniform samplerBuffer Bones;
uniform samplerBuffer Decals;
uniform vec3 uLight0Direction;
uniform vec3 uLight1Direction;
uniform int uFogShape;
uniform int uShaderMode;

out float vDistance;
out vec2 vUv;
out vec4 vVertexColor;
flat out int vLight;
flat out int vOverlay;

float fogDistance(vec3 position, int shape) {
    if (shape == 0) return length(position);
    return max(length(position.xz), abs(position.y));
}

vec4 mixLight(vec3 light0, vec3 light1, vec3 normal, vec4 color) {
    float first = max(0.0, dot(light0, normal));
    float second = max(0.0, dot(light1, normal));
    float accumulated = min(1.0, (first + second) * 0.6 + 0.4);
    return vec4(color.rgb * accumulated, color.a);
}

vec2 threadiumDecalUv(vec3 position, vec3 normal) {
    vec3 absoluteNormal = abs(normal);
    if (absoluteNormal.y >= absoluteNormal.x && absoluteNormal.y >= absoluteNormal.z) {
        return normal.y < 0.0 ? vec2(position.x, -position.z) : vec2(position.x, position.z);
    }
    if (absoluteNormal.z >= absoluteNormal.x) {
        return normal.z < 0.0 ? vec2(-position.x, -position.y) : vec2(position.x, -position.y);
    }
    return normal.x < 0.0 ? vec2(-position.z, -position.y) : vec2(position.z, -position.y);
}

void main() {
    int base = (iBoneBase + int(aBone)) * 7;
    mat4 pose = mat4(
        texelFetch(Bones, base),
        texelFetch(Bones, base + 1),
        texelFetch(Bones, base + 2),
        texelFetch(Bones, base + 3));
    vec4 normal0 = texelFetch(Bones, base + 4);
    vec4 normal1 = texelFetch(Bones, base + 5);
    vec4 normal2 = texelFetch(Bones, base + 6);
    mat3 boneNormal = mat3(normal0.xyz, normal1.xyz, normal2.xyz);
    float visible = normal0.w;
    mat4 root = mat4(iRoot0, iRoot1, iRoot2, iRoot3);
    vec4 modelPosition = root * pose * vec4(aPosition, 1.0);
    vec4 clipPosition = uProjection * uModelView * modelPosition;
    gl_Position = visible > 0.5 ? clipPosition : vec4(2.0, 2.0, 2.0, 1.0);

    mat3 rootNormal = transpose(inverse(mat3(root)));
    vec3 normal = normalize(rootNormal * boneNormal * aNormal);
    bool directional = uShaderMode == 0 || uShaderMode == 1 || uShaderMode == 3 || uShaderMode == 10;
    bool whiteVertexColor = uShaderMode == 7 || uShaderMode == 8;
    vVertexColor = whiteVertexColor
        ? vec4(1.0)
        : (directional ? mixLight(uLight0Direction, uLight1Direction, normal, iTint) : iTint);

    if (uShaderMode == 8) {
        int decal = iDecalBase * 7;
        mat4 inverseTexture = mat4(
            texelFetch(Decals, decal),
            texelFetch(Decals, decal + 1),
            texelFetch(Decals, decal + 2),
            texelFetch(Decals, decal + 3));
        vec4 inverseNormal0 = texelFetch(Decals, decal + 4);
        vec4 inverseNormal1 = texelFetch(Decals, decal + 5);
        vec4 inverseNormal2 = texelFetch(Decals, decal + 6);
        mat3 inverseNormal = mat3(inverseNormal0.xyz, inverseNormal1.xyz, inverseNormal2.xyz);
        vec3 decalPosition = (inverseTexture * modelPosition).xyz;
        vec3 decalNormal = normalize(inverseNormal * normal);
        vUv = threadiumDecalUv(decalPosition, decalNormal) * inverseNormal0.w;
    } else {
        vUv = (uShaderMode == 3 || uShaderMode == 4 || uShaderMode == 7)
            ? (uTextureMatrix * vec4(aUv, 0.0, 1.0)).xy
            : aUv;
    }
    vDistance = fogDistance(modelPosition.xyz, uFogShape);
    vLight = iLight;
    vOverlay = iOverlay;
}
