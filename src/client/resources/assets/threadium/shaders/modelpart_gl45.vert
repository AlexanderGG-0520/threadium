#version 450 core
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
layout(location=3) in float aBone;
layout(std140,binding=0) uniform Projection { mat4 uProjection; };
struct Bone { mat4 pose; vec4 normal0; vec4 normal1; vec4 normal2; };
layout(std430,binding=1) readonly buffer Bones { Bone bones[]; };
struct Instance { mat4 root; ivec4 meta; vec4 tint; };
layout(std430,binding=2) readonly buffer Instances { Instance instances[]; };
uniform int uInstanceBase;
uniform int uDebugMode;
out vec2 vUv; out vec3 vNormal; flat out int vLight; flat out int vOverlay; out vec4 vTint;
void main(){Instance i=instances[uInstanceBase+gl_InstanceID];Bone b=bones[i.meta.x+int(aBone)];if(uDebugMode==1)gl_Position=vec4(aPosition.xy*.12+vec2(-.65,0),0,1);else if(uDebugMode==3)gl_Position=uProjection*i.root*vec4(aPosition,1);else if(uDebugMode==4)gl_Position=uProjection*b.pose*vec4(aPosition,1);else if(uDebugMode==5)gl_Position=uProjection*vec4(aPosition,1);else gl_Position=uProjection*i.root*b.pose*vec4(aPosition,1.0);vNormal=normalize(mat3(b.normal0.xyz,b.normal1.xyz,b.normal2.xyz)*aNormal);vUv=aUv;vLight=i.meta.y;vOverlay=i.meta.z;vTint=i.tint;}
