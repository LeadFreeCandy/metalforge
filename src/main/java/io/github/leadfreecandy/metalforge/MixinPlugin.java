package io.github.leadfreecandy.metalforge;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies a mixin only if its optimization (the package after "mixin.") is enabled in {@link Options}. */
public final class MixinPlugin implements IMixinConfigPlugin {
    private static final String PREFIX = "io.github.leadfreecandy.metalforge.mixin.";
    /** Optimizations that patch another mod: applied only when that mod is installed. */
    private static final java.util.Map<String, String> NEEDS_MOD = java.util.Map.of("lootr_far", "lootr", "toro_light", "torohealth",
        "infra", "sodium", "alpha_const", "sodium", "cutout_sort", "sodium");

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(PREFIX)) return true;
        String rest = mixinClassName.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        String opt = dot < 0 ? rest : rest.substring(0, dot);
        String mod = NEEDS_MOD.get(opt);
        if (mod != null && net.neoforged.fml.loading.LoadingModList.get().getModFileById(mod) == null) return false;
        if (opt.equals("infra")) return true;            // shared hooks; each optimization still gates itself
        return Options.enabled(opt);
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.equals(PREFIX + "toro_light.LevelRendererToroMixin")) {
            boolean ok = io.github.leadfreecandy.metalforge.opt.ToroLight.patch(targetClass);
            org.slf4j.LoggerFactory.getLogger("MetalForge").info("toro_light: {}", ok ? "patched ToroHealth's in-world bar hook" : "ToroHealth hook not found, unchanged");
        }
    }
}
