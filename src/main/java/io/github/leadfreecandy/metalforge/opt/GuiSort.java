package io.github.leadfreecandy.metalforge.opt;

import java.util.Comparator;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * GuiRenderer's ELEMENT_SORT_COMPARATOR written out by hand: scissor (nulls first; top, bottom, left, right), then
 * pipeline sort key, then texture setup sort key (nulls first). Same keys, same order, same calls, so the stable sort gives
 * the identical list; vanilla's Comparator.comparing chain boxes every int key on every comparison.
 */
public final class GuiSort {
    private GuiSort() {}

    public static final Comparator<GuiElementRenderState> ELEMENTS = GuiSort::compare;

    private static int compare(GuiElementRenderState a, GuiElementRenderState b) {
        int c = scissor(a.scissorArea(), b.scissorArea());
        if (c != 0) return c;
        c = Integer.compare(a.pipeline().getSortKey(), b.pipeline().getSortKey());
        if (c != 0) return c;
        TextureSetup ta = a.textureSetup(), tb = b.textureSetup();
        if (ta == tb) return 0;
        if (ta == null) return -1;
        if (tb == null) return 1;
        return Integer.compare(ta.getSortKey(), tb.getSortKey());
    }

    private static int scissor(ScreenRectangle a, ScreenRectangle b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        int c = Integer.compare(a.top(), b.top());
        if (c != 0) return c;
        c = Integer.compare(a.bottom(), b.bottom());
        if (c != 0) return c;
        c = Integer.compare(a.left(), b.left());
        if (c != 0) return c;
        return Integer.compare(a.right(), b.right());
    }
}
