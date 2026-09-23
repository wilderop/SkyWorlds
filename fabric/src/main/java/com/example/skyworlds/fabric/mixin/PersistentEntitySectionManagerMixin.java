package com.example.skyworlds.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

/**
 * Vanilla {@code ServerLevel.addPlayer} force-removes a same-UUID player then
 * immediately {@code addNewEntity}s. {@code player.remove()} only marks the
 * ghost; the UUID stays in {@code knownUuids} until a later tick. Reconnects
 * (timeouts, sky resume) then fail with "UUID of added entity already exists"
 * and leave extra ServerPlayer copies that eat block-break packets.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> {
    @Shadow
    @Final
    private Set<UUID> knownUuids;

    @Shadow
    @Final
    private EntityLookup<T> visibleEntityStorage;

    @Inject(method = "addEntityUuid", at = @At("HEAD"))
    private void skyworlds$evictStalePlayerUuid(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof ServerPlayer incoming)) {
            return;
        }
        UUID id = incoming.getUUID();
        if (!this.knownUuids.contains(id)) {
            return;
        }
        T existing = this.visibleEntityStorage.getEntity(id);
        if (existing == incoming) {
            return;
        }
        if (existing instanceof ServerPlayer old) {
            try {
                if (old.level() instanceof ServerLevel level) {
                    level.removePlayerImmediately(old, Entity.RemovalReason.DISCARDED);
                }
            } catch (Exception ignored) {
            }
            try {
                old.discard();
            } catch (Exception ignored) {
            }
            try {
                this.visibleEntityStorage.remove(existing);
            } catch (Exception ignored) {
            }
        }
        this.knownUuids.remove(id);
    }
}
