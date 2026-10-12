package io.github.leadfreecandy.metalforge.metal.mixin.sodium;

import io.github.leadfreecandy.metalforge.metal.MetalSodium;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** On the Metal backend, Sodium's command list allocates and copies Metal memory instead of GL buffers. */
@Mixin(value = GLRenderDevice.class, remap = false)
public abstract class GLRenderDeviceMixin {
	@Inject(method = "createCommandList", at = @At("HEAD"), cancellable = true)
	private void metalforge$commandList(CallbackInfoReturnable<CommandList> cir) {
		if (MetalSodium.active()) cir.setReturnValue(MetalSodium.commandList());
	}
}
