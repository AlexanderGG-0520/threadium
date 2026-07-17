#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(texture(InSampler, texCoord).r, 0.0, 0.0, 1.0);
}
