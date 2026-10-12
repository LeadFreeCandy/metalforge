package io.github.leadfreecandy.metalforge.mixin.gui_sort;

import io.github.leadfreecandy.metalforge.opt.GuiSort;
import java.util.Comparator;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Sorts GUI elements with an unboxed comparator that orders exactly like vanilla's (see GuiSort). */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererSortMixin {
    @ModifyArg(method = "prepare", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;sortElements(Ljava/util/Comparator;)V"))
    private Comparator<?> metalforge$comparator(Comparator<?> vanilla) {
        return GuiSort.ELEMENTS;
    }
}
