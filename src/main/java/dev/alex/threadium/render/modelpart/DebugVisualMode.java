package dev.alex.threadium.render.modelpart;

public enum DebugVisualMode {
    OFF("off"), SCREEN_TRIANGLE("screen_triangle"), SCREEN_TRIANGLE_MAIN_TARGET("screen_triangle_main_target"), MESH_CLIP_SPACE("mesh_clip_space"), MESH_MAGENTA("mesh_magenta"),
    MESH_NO_DEPTH("mesh_no_depth"), MESH_NO_CULL("mesh_no_cull"), MESH_IDENTITY_BONE("mesh_identity_bone"),
    MESH_IDENTITY_ROOT("mesh_identity_root"), MESH_PROJECTION_ONLY("mesh_projection_only"), NORMAL("normal");
    private final String configName; DebugVisualMode(String value){configName=value;} public String configName(){return configName;}
    public static DebugVisualMode parse(String value){for(DebugVisualMode mode:values())if(mode.configName.equalsIgnoreCase(value.trim()))return mode;throw new IllegalArgumentException("Unknown entity.gpu.debugVisualMode: "+value);}
    public boolean overlayOnly(){return this==SCREEN_TRIANGLE||this==SCREEN_TRIANGLE_MAIN_TARGET||this==MESH_CLIP_SPACE;}
    public boolean worldSpaceDiagnostic(){return switch(this){
        case MESH_MAGENTA,MESH_NO_DEPTH,MESH_NO_CULL,MESH_IDENTITY_BONE,MESH_IDENTITY_ROOT,MESH_PROJECTION_ONLY->true;
        case OFF,SCREEN_TRIANGLE,SCREEN_TRIANGLE_MAIN_TARGET,MESH_CLIP_SPACE,NORMAL->false;
    };}
    public int shaderCode(){return switch(this){case MESH_CLIP_SPACE->1;case MESH_MAGENTA,MESH_NO_DEPTH,MESH_NO_CULL->2;case MESH_IDENTITY_BONE->3;case MESH_IDENTITY_ROOT->4;case MESH_PROJECTION_ONLY->5;default->0;};}
}
