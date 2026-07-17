#version 450 core
layout(binding=0) uniform sampler2D Sampler0; layout(binding=1) uniform sampler2D Sampler1; layout(binding=2) uniform sampler2D Sampler2;
in vec2 vUv; in vec3 vNormal; flat in int vLight; flat in int vOverlay; in vec4 vTint; layout(location=0) out vec4 outColor;
uniform int uDebugMode;
void main(){if(uDebugMode!=0){outColor=vec4(1,0,1,1);return;}vec4 color=texture(Sampler0,vUv)*vTint;if(color.a<0.1)discard;ivec2 light=ivec2(vLight&65535,(vLight>>16)&65535)/16;ivec2 overlay=ivec2(vOverlay&65535,(vOverlay>>16)&65535)/16;vec4 o=texelFetch(Sampler1,overlay,0);color.rgb=mix(o.rgb,color.rgb,o.a);outColor=color*texelFetch(Sampler2,light,0);}
