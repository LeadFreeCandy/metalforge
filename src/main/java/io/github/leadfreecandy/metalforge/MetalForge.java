package io.github.leadfreecandy.metalforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MetalForge: client-side performance overhaul. Every optimization is a mixin group that can be toggled, see {@link Options}. */
@Mod(value = MetalForge.MOD_ID, dist = Dist.CLIENT)
public final class MetalForge {
    public static final String MOD_ID = "metalforge";
    public static final Logger LOG = LoggerFactory.getLogger("MetalForge");

    public MetalForge() {
        LOG.info("MetalForge loaded; optimizations: {}", Options.describe());
    }
}
