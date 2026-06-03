package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;

import java.util.ArrayList;
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
        // Only inject #define VOXY when the active shader pack has voxy.json support AND
        // IrisVoxyRenderPipeline will be used. Without it the Voxy code paths in shader packs
        // like Photon would sample vxDepthTexOpaque which is only provided by IrisVoxyRenderPipeline.
        if (IrisVoxyRenderPipelineData.voxyPackHasSupport) {
            var mutableList = new ArrayList<>(list);
            define(mutableList, "VOXY");
            return original.call(mutableList);
        }
        return original.call(list);
    }
}
