package io.github.leadfreecandy.metalforge.opt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Allocation-free "would BlockCollisions (source=null) yield nothing for this box?" (same cursor, same filters). */
public final class FastCollide {
    private static final ThreadLocal<BlockPos.MutableBlockPos> POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private FastCollide() {}

    /** true only if certainly no block collider; false means "ask vanilla". */
    public static boolean noBlockCollisions(Level level, AABB box) {
        int x0 = Mth.floor(box.minX - 1.0E-7) - 1, x1 = Mth.floor(box.maxX + 1.0E-7) + 1;
        int y0 = Mth.floor(box.minY - 1.0E-7) - 1, y1 = Mth.floor(box.maxY + 1.0E-7) + 1;
        int z0 = Mth.floor(box.minZ - 1.0E-7) - 1, z1 = Mth.floor(box.maxZ + 1.0E-7) + 1;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 64) return false;   // big boxes: vanilla
        BlockPos.MutableBlockPos pos = POS.get();
        CollisionContext ctx = CollisionContext.empty();
        BlockGetter chunk = null;
        int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
        for (int x = x0; x <= x1; x++) {
            int tx = (x == x0 || x == x1) ? 1 : 0;
            for (int z = z0; z <= z1; z++) {
                int tz = (z == z0 || z == z1) ? 1 : 0;
                int cx = SectionPos.blockToSectionCoord(x), cz = SectionPos.blockToSectionCoord(z);
                if (cx != chunkX || cz != chunkZ) {
                    chunk = level.getChunkForCollisions(cx, cz);
                    chunkX = cx;
                    chunkZ = cz;
                }
                if (chunk == null) continue;
                for (int y = y0; y <= y1; y++) {
                    int type = tx + tz + ((y == y0 || y == y1) ? 1 : 0);
                    if (type == 3) continue;
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (type == 1 && !state.hasLargeCollisionShape()) continue;
                    if (type == 2 && !state.is(Blocks.MOVING_PISTON)) continue;
                    if (!ctx.getCollisionShape(state, level, pos).isEmpty()) return false;
                }
            }
        }
        return true;
    }
}
