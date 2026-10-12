package io.github.leadfreecandy.metalforge.metal.mixin.sodium;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the Sodium-on-Metal mixins only when Sodium is installed. */
public final class SodiumPlugin implements IMixinConfigPlugin {
	private static Boolean present;

	private static boolean sodium() {
		if (present == null) {
			try {
				present = net.neoforged.fml.loading.LoadingModList.get().getModFileById("sodium") != null;
			} catch (Throwable t) {
				present = false;
			}
		}
		return present;
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return sodium(); }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	/**
	 * UniformBufferManager casts the game's section-time GpuBuffer to Sodium's GlBufferAccessor to read its GL name; on Metal the
	 * buffer is a MetalBuffer and the CHECKCAST itself throws. Replace each "CHECKCAST GlBufferAccessor; INVOKEINTERFACE
	 * sodium$getHandle" pair with a static call that answers 0 on Metal and does the cast + call otherwise.
	 */
	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		if (!targetClassName.endsWith("UniformBufferManager")) return;
		for (org.objectweb.asm.tree.MethodNode m : targetClass.methods) {
			for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof org.objectweb.asm.tree.TypeInsnNode cast && cast.getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST
					&& cast.desc.equals("net/caffeinemc/mods/sodium/mixin/core/GlBufferAccessor")
					&& cast.getNext() instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("sodium$getHandle")) {
					m.instructions.remove(cast);
					m.instructions.set(call, new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
						"io/github/leadfreecandy/metalforge/metal/MetalSodium", "glHandle", "(Ljava/lang/Object;)I", false));
				}
			}
		}
	}
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
