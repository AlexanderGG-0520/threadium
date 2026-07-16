package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/** Mirrors PreparedRenderType's output-target selection for Threadium raw GL draws. */
final class OpenGlRenderTarget implements AutoCloseable {
    private int framebuffer;
    record Binding(int framebuffer,int width,int height,int status,int colorTexture,int colorTarget,int colorLevel,int depthTexture,int depthTarget,int depthLevel,int samples) {}
    Binding bind(PreparedRenderType prepared){
        return bind(prepared.outputTarget().getRenderTarget());
    }
    Binding bind(RenderTarget target){if(framebuffer==0)framebuffer=GL30C.glGenFramebuffers();GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,framebuffer);
        if(!(target.getColorTextureView() instanceof GlTextureView color))throw new IllegalStateException("non-OpenGL color target");
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,color.glId(),color.fboMipLevel());GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        if(target.useDepth&&target.getDepthTextureView() instanceof GlTextureView depth)GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER,GL30C.GL_DEPTH_ATTACHMENT,GL11C.GL_TEXTURE_2D,depth.glId(),depth.fboMipLevel());else GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER,GL30C.GL_DEPTH_ATTACHMENT,GL11C.GL_TEXTURE_2D,0,0);
        int depthId=target.useDepth&&target.getDepthTextureView() instanceof GlTextureView depth?depth.glId():0;
        GL11C.glViewport(0,0,target.width,target.height);return new Binding(framebuffer,target.width,target.height,GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER),color.glId(),GL11C.GL_TEXTURE_2D,color.fboMipLevel(),depthId,GL11C.GL_TEXTURE_2D,depthId==0?0:((GlTextureView)target.getDepthTextureView()).fboMipLevel(),1);
    }
    @Override public void close(){if(framebuffer!=0)GL30C.glDeleteFramebuffers(framebuffer);framebuffer=0;}
}
