#version 330 core
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
layout(location=3) in float aBone;
layout(location=4) in vec4 iRoot0; layout(location=5) in vec4 iRoot1; layout(location=6) in vec4 iRoot2; layout(location=7) in vec4 iRoot3;
layout(location=8) in int iBoneBase; layout(location=9) in int iLight; layout(location=10) in int iOverlay; layout(location=11) in vec4 iTint;
layout(std140) uniform Projection { mat4 uProjection; };
uniform samplerBuffer Bones;
uniform int uDebugMode;
out vec2 vUv; out vec3 vNormal; flat out int vLight; flat out int vOverlay; out vec4 vTint;
void main(){int base=(iBoneBase+int(aBone))*7;mat4 pose=mat4(texelFetch(Bones,base),texelFetch(Bones,base+1),texelFetch(Bones,base+2),texelFetch(Bones,base+3));mat3 normalMatrix=mat3(texelFetch(Bones,base+4).xyz,texelFetch(Bones,base+5).xyz,texelFetch(Bones,base+6).xyz);mat4 root=mat4(iRoot0,iRoot1,iRoot2,iRoot3);if(uDebugMode==1)gl_Position=vec4(aPosition.xy*.12+vec2(-.65,0),0,1);else if(uDebugMode==3)gl_Position=uProjection*root*vec4(aPosition,1);else if(uDebugMode==4)gl_Position=uProjection*pose*vec4(aPosition,1);else if(uDebugMode==5)gl_Position=uProjection*vec4(aPosition,1);else gl_Position=uProjection*root*pose*vec4(aPosition,1.0);vNormal=normalize(normalMatrix*aNormal);vUv=aUv;vLight=iLight;vOverlay=iOverlay;vTint=iTint;}
