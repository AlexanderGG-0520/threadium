#version 450 core
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
layout(location=3) in float aBone;
layout(std140,binding=0) uniform Projection { mat4 uProjection; };
struct Bone { mat4 pose; vec4 normal0; vec4 normal1; vec4 normal2; };
layout(std430,binding=1) readonly buffer Bones { Bone bones[]; };
uniform mat4 uRoot;
uniform int uBoneBase;
out vec2 vUv;
out vec3 vNormal;
void main(){ Bone b=bones[uBoneBase+int(aBone)]; gl_Position=uProjection*uRoot*b.pose*vec4(aPosition,1.0); vNormal=normalize(mat3(b.normal0.xyz,b.normal1.xyz,b.normal2.xyz)*aNormal); vUv=aUv; }
