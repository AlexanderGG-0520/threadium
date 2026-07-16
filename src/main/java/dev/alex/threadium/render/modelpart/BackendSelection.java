package dev.alex.threadium.render.modelpart;

public final class BackendSelection {
    public enum Kind { OPENGL45, OPENGL33, NONE }
    private BackendSelection() {}
    public static Kind select(String requested, GlCapabilitySet capabilities) {
        return switch (requested) {
            case "disabled" -> Kind.NONE;
            case "opengl45" -> capabilities.supportsGl45Backend() ? Kind.OPENGL45 : Kind.NONE;
            case "opengl33" -> capabilities.supportsGl33Backend() ? Kind.OPENGL33 : Kind.NONE;
            case "auto" -> capabilities.supportsGl45Backend() ? Kind.OPENGL45
                    : capabilities.supportsGl33Backend() ? Kind.OPENGL33 : Kind.NONE;
            default -> throw new IllegalArgumentException("Unknown GPU backend: " + requested);
        };
    }
}
