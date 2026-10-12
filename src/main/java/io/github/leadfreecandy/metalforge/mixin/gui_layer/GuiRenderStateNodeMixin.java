package io.github.leadfreecandy.metalforge.mixin.gui_layer;

import io.github.leadfreecandy.metalforge.opt.GuiLayerNode;
import io.github.leadfreecandy.metalforge.opt.RectIndex;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the bounds of each state added to the four lists vanilla's hasIntersection scans (not glyphs, which it skips). */
@Mixin(GuiRenderState.Node.class)
public abstract class GuiRenderStateNodeMixin implements GuiLayerNode {
    @Unique private final RectIndex metalforge$index = new RectIndex();

    @Override
    public RectIndex metalforge$index() {
        return this.metalforge$index;
    }

    @Inject(method = "addItem", at = @At("TAIL"))
    private void metalforge$item(GuiItemRenderState state, CallbackInfo ci) {
        this.metalforge$index.add(state.bounds());
    }

    @Inject(method = "addText", at = @At("TAIL"))
    private void metalforge$text(GuiTextRenderState state, CallbackInfo ci) {
        this.metalforge$index.add(state.bounds());
    }

    @Inject(method = "addPicturesInPictureState", at = @At("TAIL"))
    private void metalforge$pip(PictureInPictureRenderState state, CallbackInfo ci) {
        this.metalforge$index.add(state.bounds());
    }

    @Inject(method = "addGuiElement", at = @At("TAIL"))
    private void metalforge$element(GuiElementRenderState state, CallbackInfo ci) {
        this.metalforge$index.add(state.bounds());
    }
}
