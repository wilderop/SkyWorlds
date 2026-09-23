package com.example.skyworlds.fabric.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla DistanceManager.removePlayer NPEs when a player is not in
 * playersPerChunk (common after nether-roof pearls / same-chunk section
 * changes). That abort leaves move/portal incomplete, so the client
 * desyncs and block placement is rejected as "too far".
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @Shadow
    @Final
    private Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk;

    @Inject(method = "removePlayer", at = @At("HEAD"), cancellable = true)
    private void skyworlds$skipMissingChunkPlayers(SectionPos sectionPos, ServerPlayer player, CallbackInfo ci) {
        if (playersPerChunk.get(sectionPos.chunk().pack()) == null) {
            ci.cancel();
        }
    }
}
