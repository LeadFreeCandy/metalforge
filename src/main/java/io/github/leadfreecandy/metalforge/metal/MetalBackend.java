package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Native Metal rendering on MC 26.1.2's GpuBackend seam (the backend list in Minecraft's constructor; see MinecraftMixin), in
 * front of the GL backend, which stays as the fallback. The window: GLFW with no client API when we create it; when NeoForge's
 * early loading screen hands over its GL window instead, its context's drawable is detached and the view gets a CAMetalLayer.
 */
public final class MetalBackend implements GpuBackend {
	static {
		// METALFORGE_METAL_PROPS="k=v;k=v": backend tuning switches (metalforge.metal.*) from the environment, for harness runs.
		String env = System.getenv("METALFORGE_METAL_PROPS");
		if (env != null) {
			for (String kv : env.split(";")) {
				int eq = kv.indexOf('=');
				if (eq > 0) System.setProperty(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
			}
		}
	}

	@Override
	public String getName() {
		return "Metal (MetalForge)";
	}

	@Override
	public void setWindowHints() {
		GLFW.glfwDefaultWindowHints();
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
	}

	@Override
	public void handleWindowCreationErrors(GLFWErrorCapture.@Nullable Error error) throws BackendCreationException {
		throw new BackendCreationException(error == null ? "Failed to create window for Metal" : error.toString());
	}

	@Override
	public GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions) {
		// A window taken over from NeoForge's early loading screen has a GL context, and that screen keeps drawing its progress
		// with GL on this thread until mod loading ends: keep the context current with capabilities so those calls work (their
		// output goes nowhere once the view's layer is Metal's and the context's drawable is detached).
		if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CLIENT_API) != GLFW.GLFW_NO_API) {
			GLFW.glfwMakeContextCurrent(window);
			org.lwjgl.opengl.GL.createCapabilities();
		}
		MetalDevice device = new MetalDevice(window, defaultShaderSource);
		MetalDevice.active = device;
		return new GpuDevice(device);
	}
}
