package com.example.skyworlds.fabric.mixin;

import com.example.skyworlds.fabric.SkyWorldsFabric;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrownEnderpearl.class)
public class ThrownEnderpearlMixin {
    @Inject(method = "onHit", at = @At("HEAD"))
    private void skyworlds$stasisHit(HitResult result, CallbackInfo ci) {
        SkyWorldsFabric.onPearlHit((ThrownEnderpearl) (Object) this);
    }
}
