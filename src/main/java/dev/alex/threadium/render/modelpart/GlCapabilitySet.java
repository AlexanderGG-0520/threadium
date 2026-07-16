package dev.alex.threadium.render.modelpart;

/** Testable projection of the active-context LWJGL capabilities. */
public record GlCapabilitySet(
        boolean gl33, boolean gl40, boolean gl41, boolean gl42, boolean gl43, boolean gl44, boolean gl45,
        boolean instancedArrays, boolean drawInstanced, boolean textureBuffer, boolean uniformBuffer,
        boolean vertexArray, boolean mapBufferRange, boolean bufferStorage, boolean shaderStorage, boolean directStateAccess) {
    public boolean supportsGl33Backend() {
        return gl33 && instancedArrays && drawInstanced && textureBuffer && uniformBuffer && vertexArray;
    }
    public boolean supportsGl45Backend() {
        return gl45 && supportsGl33Backend() && bufferStorage && shaderStorage && directStateAccess;
    }
}
