#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:light.glsl>

in vec3 Position;
in vec3 Normal;
in vec2 UV0;
in float BoneIndex;
in mat4 RootMatrix;
in int BoneBase;
in int PackedLight;
in int PackedOverlay;
in int DecalBase;
in vec4 Tint;
in vec4 SpriteUvTransform;

uniform samplerBuffer Bones;
uniform samplerBuffer Decals;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
flat out int vertexLight;
flat out int vertexOverlay;

vec2 threadium_decal_uv(vec3 position, vec3 normal) {
    vec3 p = vec3(-position.x, -position.z, -position.y);
    vec3 a = abs(normal);
    if (a.y >= a.x && a.y >= a.z) {
        if (normal.y < 0.0) p = vec3(p.x, -p.y, -p.z);
    } else if (a.z >= a.x) {
        p = normal.z < 0.0 ? vec3(-p.x, p.z, p.y) : vec3(p.x, -p.z, p.y);
    } else {
        p = normal.x < 0.0 ? vec3(p.z, p.x, p.y) : vec3(-p.z, -p.x, p.y);
    }
    return -p.xy;
}

void main() {
    int base = (BoneBase + int(BoneIndex)) * 7;
    mat4 bonePose = mat4(texelFetch(Bones, base), texelFetch(Bones, base + 1), texelFetch(Bones, base + 2), texelFetch(Bones, base + 3));
    mat3 boneNormal = mat3(texelFetch(Bones, base + 4).xyz, texelFetch(Bones, base + 5).xyz, texelFetch(Bones, base + 6).xyz);
    vec4 modelPosition = RootMatrix * bonePose * vec4(Position, 1.0);
    vec4 cameraSpacePosition = ModelViewMat * modelPosition;
    gl_Position = ProjMat * cameraSpacePosition;
    sphericalVertexDistance = fog_spherical_distance(modelPosition.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(modelPosition.xyz);

    mat3 rootNormal = transpose(inverse(mat3(RootMatrix)));
    vec3 normal = normalize(rootNormal * boneNormal * Normal);
#ifdef THREADIUM_PER_FACE
    vec2 faceLight = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-faceLight, Tint);
    vertexPerFaceColorFront = minecraft_mix_light_separate(faceLight, Tint);
#elif defined(THREADIUM_GLINT) || defined(THREADIUM_CRUMBLING)
    vertexColor = vec4(1.0);
#elif defined(THREADIUM_LIT_OVERLAY) || defined(THREADIUM_CARDINAL)
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, Tint);
#else
    vertexColor = Tint;
#endif

    texCoord0 = SpriteUvTransform.xy + UV0 * SpriteUvTransform.zw;
#ifdef THREADIUM_CRUMBLING
    int decal = DecalBase * 7;
    mat4 decalPose = mat4(texelFetch(Decals, decal), texelFetch(Decals, decal + 1), texelFetch(Decals, decal + 2), texelFetch(Decals, decal + 3));
    mat3 decalNormal = mat3(texelFetch(Decals, decal + 4).xyz, texelFetch(Decals, decal + 5).xyz, texelFetch(Decals, decal + 6).xyz);
    texCoord0 = threadium_decal_uv((decalPose * modelPosition).xyz, normalize(decalNormal * normal));
#endif
#if defined(THREADIUM_LIT_TEXTURE_MATRIX) || defined(THREADIUM_EMISSIVE_TEXTURE_MATRIX) || defined(THREADIUM_GLINT)
    texCoord0 = (TextureMat * vec4(texCoord0, 0.0, 1.0)).xy;
#endif
    vertexLight = PackedLight;
    vertexOverlay = PackedOverlay;
}
