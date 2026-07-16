#version 450 core
layout(binding=0) uniform sampler2D Sampler0;
layout(binding=1) uniform sampler2D Sampler1;
layout(binding=2) uniform sampler2D Sampler2;
uniform vec4 uTint;
uniform int uLight;
uniform int uOverlay;
in vec2 vUv;
in vec3 vNormal;
layout(location=0) out vec4 outColor;
void main(){
 vec4 color=texture(Sampler0,vUv)*uTint; if(color.a<0.1)discard;
 ivec2 light=ivec2(uLight&65535,(uLight>>16)&65535)/16;
 ivec2 overlay=ivec2(uOverlay&65535,(uOverlay>>16)&65535)/16;
 vec4 overlayColor=texelFetch(Sampler1,overlay,0); color.rgb=mix(overlayColor.rgb,color.rgb,overlayColor.a);
 outColor=color*texelFetch(Sampler2,light,0);
}
