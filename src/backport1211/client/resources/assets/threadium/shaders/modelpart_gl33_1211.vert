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

uniform mat4 uProjection;
uniform mat4 uModelView;
uniform mat4 uTextureMatrix;
uniform samplerBuffer Bones;
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
    vVertexColor = directional ? mixLight(uLight0Direction, uLight1Direction, normal, iTint) : iTint;
    vUv = (uShaderMode == 3 || uShaderMode == 4 || uShaderMode == 7)
        ? (uTextureMatrix * vec4(aUv, 0.0, 1.0)).xy
        : aUv;
    vDistance = fogDistance(modelPosition.xyz, uFogShape);
    vLight = iLight;
    vOverlay = iOverlay;
}
