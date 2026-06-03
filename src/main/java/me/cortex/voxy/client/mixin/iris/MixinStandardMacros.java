package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.List;

@Mixin(value = StandardMacros.class, remap = false)
public abstract class MixinStandardMacros {
    @Shadow
    private static void define(List<StringPair> defines, String key){}

    @WrapOperation(method = "createStandardEnvironmentDefines", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableList;copyOf(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableList;"))
    private static ImmutableList<StringPair> voxy$injectVoxyDefine(Collection<StringPair> list, Operation<ImmutableList<StringPair>> original) {
        // TODO: Re-enable #define VOXY when IrisVoxyRenderPipeline is restored.
        // IrisVoxyRenderPipeline is currently disabled (stale G-buffer texture IDs).
        // Injecting #define VOXY without it causes shader packs like Photon to activate
        // their Voxy-specific code paths which sample vxDepthTexOpaque — but since
        // IrisVoxyRenderPipeline is not providing that depth texture, the sampler returns
        // garbage, producing the rainbow color band artifacts on LOD terrain.
        return ImmutableList.copyOf(list);
    }
}
