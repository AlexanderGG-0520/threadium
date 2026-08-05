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
uniform samplerBuffer Bones;
out vec2 vUv;
flat out int vLight;
flat out int vOverlay;
out vec4 vTint;
void main() {
    int base = (iBoneBase + int(aBone)) * 7;
    mat4 pose = mat4(
        texelFetch(Bones, base),
        texelFetch(Bones, base + 1),
        texelFetch(Bones, base + 2),
        texelFetch(Bones, base + 3));
    float visible = texelFetch(Bones, base + 4).w;
    mat4 root = mat4(iRoot0, iRoot1, iRoot2, iRoot3);
    vec4 position = uProjection * uModelView * root * pose * vec4(aPosition, 1.0);
    gl_Position = visible > 0.5 ? position : vec4(2.0, 2.0, 2.0, 1.0);
    vUv = aUv;
    vLight = iLight;
    vOverlay = iOverlay;
    vTint = iTint;
}
