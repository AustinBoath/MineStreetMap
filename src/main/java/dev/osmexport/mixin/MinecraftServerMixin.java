package dev.osmexport.mixin;

import dev.osmexport.OsmData;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tick hook used to record the path of the player while a line is active. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void osm$tick(CallbackInfo ci) {
        OsmData.tick((MinecraftServer) (Object) this);
    }
}
