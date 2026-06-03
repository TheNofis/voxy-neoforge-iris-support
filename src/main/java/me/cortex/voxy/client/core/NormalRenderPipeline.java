package me.cortex.voxy.client.core;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.ARBComputeShader.glDispatchCompute;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glBindImageTexture;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL15.GL_READ_WRITE;
import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL42.GL_LEQUAL;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glTextureParameterf;

public class NormalRenderPipeline extends AbstractRenderPipeline {
    private GlTexture colourTex;
    private GlTexture colourSSAOTex;
    private final GlFramebuffer fbSSAO = new GlFramebuffer();

    private final boolean useEnvFog;
    private final FullscreenBlit finalBlit;

    // Deferred blit state: when Iris is active we skip the G-buffer blit in finish() and
    // instead composite after Iris's own passes via postIrisComposite().
    private boolean hasDeferredIrisBlit = false;
    private Viewport<?> deferredIrisViewport = null;

    private final Shader ssaoCompute = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:post/ssao.comp")
            .compile();

    protected NormalRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier, false);
        this.useEnvFog = VoxyConfig.CONFIG.useEnvironmentalFog;
        this.finalBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag",
                a->a.defineIf("USE_ENV_FOG", this.useEnvFog).define("EMIT_COLOUR"));
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFB, int srcWidth, int srcHeight) {
        if (this.colourTex == null || this.colourTex.getHeight() != viewport.height || this.colourTex.getWidth() != viewport.width) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
            }
            this.fb.resize(viewport.width, viewport.height);

            this.colourTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
            this.colourSSAOTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);

            this.fb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.colourTex).verify();
            this.fbSSAO.bind(this.fb.getDepthAttachmentType(), this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourSSAOTex).verify();


            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.fb.getDepthTex().id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
        }

        this.initDepthStencil(sourceFB, this.fb.framebuffer.id, viewport.width, viewport.height, viewport.width, viewport.height);

        return this.fb.getDepthTex().id;
    }

    // Whether SSAO was skipped this frame (set in postOpaquePreTranslucent, read in finish)
    private boolean skipSsaoThisFrame = false;

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        // Skip SSAO when Iris is active — the viewport MVP is derived from Iris's modified
        // projection (different near/far), causing SSAO to over-darken dense foliage.
        // Iris/Photon then applies its atmospheric scattering to those over-darkened pixels,
        // tinting them purple. Use raw block colors instead.
        if (IrisUtil.IRIS_INSTALLED && IrisUtil.irisShaderPackEnabled()) {
            this.skipSsaoThisFrame = true;
            glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
            return;
        }
        this.skipSsaoThisFrame = false;

        this.ssaoCompute.bind();
        try (var stack = MemoryStack.stackPush()) {
            long ptr = stack.nmalloc(4*4*4);
            viewport.MVP.getToAddress(ptr);
            nglUniformMatrix4fv(3, 1, false, ptr);//MVP
            viewport.MVP.invert(new Matrix4f()).getToAddress(ptr);
            nglUniformMatrix4fv(4, 1, false, ptr);//invMVP
        }


        glBindImageTexture(0, this.colourSSAOTex.id, 0, false,0, GL_READ_WRITE, GL_RGBA8);
        glBindTextureUnit(1, this.fb.getDepthTex().id);
        glBindTextureUnit(2, this.colourTex.id);

        glDispatchCompute((viewport.width+31)/32, (viewport.height+31)/32, 1);

        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        if (IrisUtil.IRIS_INSTALLED && IrisUtil.irisShaderPackEnabled()) {
            // When Iris is active, don't blit into Iris's G-buffer here.
            // Blitting already-lit LOD colors into the G-buffer causes Iris's deferred pipeline
            // to apply lighting a second time (wrong normals → atmospheric pink tint, TAA trails).
            // Instead we defer to postIrisComposite() which runs after Iris composites.
            glDisable(GL_STENCIL_TEST);
            glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
            this.hasDeferredIrisBlit = true;
            this.deferredIrisViewport = viewport;
            return;
        }

        this.finalBlit.bind();
        if (this.useEnvFog) {
            glUniform4f(4, 0, 0, 0, 0);
            glUniform4f(5, 0, 0, 0, 0);
        }

        // Use raw colors (colourTex) when SSAO was skipped, SSAO-processed otherwise
        glBindTextureUnit(3, this.skipSsaoThisFrame ? this.colourTex.id : this.colourSSAOTex.id);

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        AbstractRenderPipeline.transformBlitDepth(this.finalBlit, this.fb.getDepthTex().id, sourceFrameBuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        glDisable(GL_BLEND);
    }

    @Override
    public void postIrisComposite(int mainFB, int srcWidth, int srcHeight) {
        if (!this.hasDeferredIrisBlit || this.deferredIrisViewport == null) return;
        var viewport = this.deferredIrisViewport;
        this.hasDeferredIrisBlit = false;
        this.deferredIrisViewport = null;

        this.finalBlit.bind();
        if (this.useEnvFog) {
            glUniform4f(4, 0, 0, 0, 0);
            glUniform4f(5, 0, 0, 0, 0);
        }
        // SSAO is always skipped when Iris is active, so always use raw colourTex here.
        glBindTextureUnit(3, this.colourTex.id);

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        // After Iris compositing the main-FB depth buffer is not in a reliable state for depth
        // testing (composite full-screen quads may have written 0.0 or incorrect values).
        // Use GL_ALWAYS so LOD pixels always pass the depth test. The stencil mask applied
        // during LOD rendering already guarantees colourTex only contains pixels where vanilla
        // terrain was absent, so LOD won't incorrectly overlay close vanilla geometry.
        glDepthFunc(GL_ALWAYS);
        AbstractRenderPipeline.transformBlitDepth(this.finalBlit, this.fb.getDepthTex().id, mainFB, viewport,
                new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        glDepthFunc(GL_LEQUAL);

        glDisable(GL_BLEND);

        // Restore state for subsequent Iris rendering (particles, weather, etc.)
        glUseProgram(0);
        glEnable(GL_DEPTH_TEST);
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    public void free() {
        this.finalBlit.delete();
        this.ssaoCompute.free();
        this.fbSSAO.free();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
        }
        super.free0();
    }
}
