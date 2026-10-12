package io.github.leadfreecandy.metalforge.mixin.infra;

import io.github.leadfreecandy.metalforge.shader.ShaderPatches;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Infrastructure (always applied): routes Sodium shader sources through {@link ShaderPatches}. */
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void metalforge$patch(Identifier name, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(ShaderPatches.apply(name.getNamespace() + ":" + name.getPath(), cir.getReturnValue()));
    }
}
