package io.github.leadfreecandy.metalforge.mixin.gui_layer;

import io.github.leadfreecandy.metalforge.opt.GuiLayerNode;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every GUI state submitted is placed above the highest node holding something it overlaps; vanilla finds that node by
 * testing the new bounds against every state in each node from the top down, which is quadratic in a busy layer (FTB Chunks'
 * minimap icons: ~3.5% of the render thread in the pack). Same walk, same answer, but each node answers from its RectIndex.
 */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
    @Shadow @Final private List<GuiRenderState.Node> strata;
    @Shadow private GuiRenderState.Node current;

    @Shadow public abstract void up();

    @Inject(method = "navigateToAboveHighestElementWithIntersectingBounds", at = @At("HEAD"), cancellable = true)
    private void metalforge$navigate(ScreenRectangle bounds, CallbackInfo ci) {
        GuiRenderState.Node node = this.strata.getLast();
        while (node.up != null) node = node.up;
        boolean found = false;
        while (true) {
            found = ((GuiLayerNode) node).metalforge$index().intersects(bounds);
            if (found || node.parent == null) break;
            node = node.parent;
        }
        this.current = node;
        if (found) this.up();
        ci.cancel();
    }
}
