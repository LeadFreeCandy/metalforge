package io.github.leadfreecandy.metalforge.opt;

import io.github.leadfreecandy.metalforge.opt.RectIndex;

/** Added to GuiRenderState.Node: the bounds of every state the node holds (elements, items, texts, pictures-in-picture). */
public interface GuiLayerNode {
    RectIndex metalforge$index();
}
