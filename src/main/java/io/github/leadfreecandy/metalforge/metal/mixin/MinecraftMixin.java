package io.github.leadfreecandy.metalforge.metal.mixin;

import com.mojang.blaze3d.systems.GpuBackend;
import io.github.leadfreecandy.metalforge.metal.MetalBackend;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Puts the Metal backend in front of OpenGL in the list Minecraft tries (-Dmetalforge.metal=false keeps OpenGL; off while Iris is installed). */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@ModifyVariable(method = "<init>", at = @At(value = "STORE"), ordinal = 0)
	private GpuBackend[] metalforge$preferMetal(GpuBackend[] backends) {
		// Apple Silicon Macs only (the native backend is arm64); -Dmetalforge.metal=false keeps OpenGL
		if (!Boolean.parseBoolean(System.getProperty("metalforge.metal", "true")) || !System.getProperty("os.name", "").startsWith("Mac")
			|| !"aarch64".equals(System.getProperty("os.arch"))) return backends;
		// Iris draws with OpenGL directly (and casts the device to the GL one at startup): stay on OpenGL when it's installed.
		try {
			if (net.neoforged.fml.loading.LoadingModList.get().getModFileById("iris") != null) {
				com.mojang.logging.LogUtils.getLogger().warn("MetalForge Metal: Iris is installed (OpenGL only); using OpenGL. Remove Iris to use Metal.");
				return backends;
			}
		} catch (Throwable ignored) {
		}
		GpuBackend[] out = new GpuBackend[backends.length + 1];
		out[0] = new MetalBackend();
		System.arraycopy(backends, 0, out, 1, backends.length);
		return out;
	}
}
