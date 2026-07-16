package dev.alex.threadium.render.modelpart;

import dev.alex.threadium.ThreadiumClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/** Independently owned, frame-coalesced diagnostics. It never touches production queues or state. */
final class ModelPartDiagnosticOverlay implements AutoCloseable {
    private final ModelPartGpuMetrics metrics;
    private final OpenGlScreenTriangle triangle=new OpenGlScreenTriangle();
    private final OpenGlRenderTarget targetFbo=new OpenGlRenderTarget();
    private final OverlayRequestCoalescer requests=new OverlayRequestCoalescer();
    private final OneShotDiagnosticLatch readback=new OneShotDiagnosticLatch();
    private boolean initialized,failed,loggedDraw,loggedRestoreError;

    ModelPartDiagnosticOverlay(ModelPartGpuMetrics metrics){this.metrics=metrics;}
    void beginFrame(DebugVisualMode mode){requests.beginFrame(mode==DebugVisualMode.SCREEN_TRIANGLE||mode==DebugVisualMode.SCREEN_TRIANGLE_MAIN_TARGET);if(mode==DebugVisualMode.SCREEN_TRIANGLE||mode==DebugVisualMode.SCREEN_TRIANGLE_MAIN_TARGET)metrics.diagnosticOverlayRequests.increment();}
    void drawWorldTail(DebugVisualMode mode){if(mode==DebugVisualMode.SCREEN_TRIANGLE)draw(mode,"LevelRenderer.render:TAIL",false);}
    void drawMainTargetTail(DebugVisualMode mode){if(mode==DebugVisualMode.SCREEN_TRIANGLE_MAIN_TARGET)draw(mode,"GameRenderer.render:TAIL (after GUI, before windowSurface.blitFromTexture)",true);}
    private void draw(DebugVisualMode mode,String hook,boolean mainTarget){
        if(failed||!requests.consume())return;OpenGlStateSnapshot state=null;
        try{
            state=OpenGlStateSnapshot.capture();int activeDrawBefore=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),activeReadBefore=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);int errorBeforeBind=GL11C.glGetError();
            if(!initialized){triangle.initialize();initialized=true;}
            var minecraft=Minecraft.getInstance();var target=minecraft.gameRenderer.mainRenderTarget();var binding=targetFbo.bind(target);int errorAfterBind=GL11C.glGetError();
            if(readback.acquire()){
                VisualDiagnosticMetrics.readbackAttempts.incrementAndGet();var result=triangle.draw(mode.configName(),hook,mainTarget,binding,errorBeforeBind,errorAfterBind);record(result,binding,triangle.vao());
            }else if(!triangle.drawOnly(binding)){VisualDiagnosticMetrics.glErrors.incrementAndGet();}
            metrics.diagnosticOverlayDraws.increment();if(!loggedDraw){loggedDraw=true;ThreadiumClient.LOGGER.info("{} fullscreen overlay submitted at {}: hookEntryDrawFbo={}, hookEntryReadFbo={}, diagnosticMainTextureFbo={}, target={}x{}",mode.configName(),hook,activeDrawBefore,activeReadBefore,binding.framebuffer(),binding.width(),binding.height());}
        }catch(Throwable failure){failed=true;metrics.diagnosticOverlayFailures.increment();VisualDiagnosticMetrics.readbackFailures.incrementAndGet();VisualDiagnosticMetrics.glErrors.incrementAndGet();ThreadiumClient.LOGGER.error("GPU ModelPart screen diagnostic failed; production renderer was not affected",failure);}
        finally{if(state!=null){state.restore();int restoreError=GL11C.glGetError();if(restoreError!=GL11C.GL_NO_ERROR){VisualDiagnosticMetrics.glErrors.incrementAndGet();if(!loggedRestoreError){loggedRestoreError=true;ThreadiumClient.LOGGER.error("Threadium screen diagnostic state restoration GL error: 0x{}",Integer.toHexString(restoreError));}}}}
    }
    private static void record(OpenGlScreenTriangle.DrawResult result,OpenGlRenderTarget.Binding binding,int vao){
        if(result.readbackCompleted())VisualDiagnosticMetrics.readbackSuccesses.incrementAndGet();else VisualDiagnosticMetrics.readbackFailures.incrementAndGet();if(result.beforeMagenta())VisualDiagnosticMetrics.beforeMagenta.incrementAndGet();if(result.afterMagenta())VisualDiagnosticMetrics.afterMagenta.incrementAndGet();
        switch(result.result()){
            case INVALID_TARGET->{if(vao==0)VisualDiagnosticMetrics.invalidVao.incrementAndGet();else VisualDiagnosticMetrics.invalidTarget.incrementAndGet();ThreadiumClient.LOGGER.error("Threadium screen diagnostic invalid target: fbo={}, colorTexture={}, vao={}, dimensions={}x{}",binding.framebuffer(),binding.colorTexture(),vao,binding.width(),binding.height());}
            case FRAMEBUFFER_INCOMPLETE->VisualDiagnosticMetrics.incomplete.incrementAndGet();
            case GL_ERROR->{VisualDiagnosticMetrics.readbackGlErrors.incrementAndGet();VisualDiagnosticMetrics.glErrors.incrementAndGet();}
            case NOT_RUN,DRAW_DID_NOT_WRITE_MAGENTA,DRAW_WROTE_MAGENTA_TO_PREPARED_TARGET,DRAW_WROTE_MAGENTA_TO_MAIN_TARGET->{}
        }
    }
    void reset(){requests.clear();readback.reset();failed=false;loggedDraw=false;loggedRestoreError=false;if(initialized)triangle.close();targetFbo.close();initialized=false;}
    long frame(){return requests.frame();}boolean requested(){return requests.requested();}
    @Override public void close(){reset();}
}
