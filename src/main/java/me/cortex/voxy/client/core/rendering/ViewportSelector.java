package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.util.IrisUtil;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ViewportSelector <T extends Viewport<?>> {
    public static final boolean VIVECRAFT_INSTALLED = ModList.get() != null && ModList.get().isLoaded("vivecraft");

    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
    }

    private T getOrCreate(Object holder) {
        return this.extraViewports.computeIfAbsent(holder, a -> this.creator.get());
    }

    private static final Object IRIS_SHADOW_OBJECT = new Object();

    public T getViewport() {
        // Skip LOD rendering during shadow pass — rendering to Iris G-buffer targets
        // during the shadow pass causes GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT because
        // shadow and main-scene render targets are incompatible.
        // LOD shadows require dedicated shader pack support (voxy_shadow.glsl etc.)
        // which is not yet implemented. renderOpaque(null) is a safe no-op.
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.defaultViewport;
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(Viewport::delete);
        this.extraViewports.clear();
    }
}
