package io.github.leadfreecandy.metalforge.mixin.particle_collide;

import io.github.leadfreecandy.metalforge.opt.FastCollide;
import java.util.List;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every moving particle with physics runs Entity.collideBoundingBox each tick: a VoxelShape for its box, an iterator
 * and a list builder, and ~27 block lookups with shape moves. Most particles are in open air. Check the same blocks
 * with vanilla's own filters first; if none has a collision shape the result is "no collision" (movement unchanged),
 * exactly what vanilla returns. Otherwise run vanilla.
 */
@Mixin(Particle.class)
public abstract class ParticleMixin {
    @Redirect(method = "move", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 metalforge$collide(Entity source, Vec3 movement, AABB box, Level level, List<VoxelShape> entityColliders) {
        if (source == null && entityColliders.isEmpty() && FastCollide.noBlockCollisions(level, box.expandTowards(movement))) return movement;
        return Entity.collideBoundingBox(source, movement, box, level, entityColliders);
    }
}
