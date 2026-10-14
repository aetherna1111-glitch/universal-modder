package dev.luminary.fabric.mixin;

import dev.luminary.core.npc.relationship.KarmaEffects;
import dev.luminary.fabric.LuminaryRuntime;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Traders know a player's name. After vanilla has set the reputation-based special prices for a customer, a player
 * whose karma with the NPCs is below zero pays more: up to three quarters extra at karma -100
 * ({@link KarmaEffects#markup}). Villagers only (every villager-derived trader of other mods included); the wandering
 * trader keeps no per-customer prices and is left alone.
 */
@Mixin(VillagerEntity.class)
abstract class VillagerPricesMixin {
	@Inject(method = "prepareOffersFor", at = @At("TAIL"))
	private void luminary$karmaMarkup(PlayerEntity player, CallbackInfo ci) {
		LuminaryRuntime.get().ifPresent(rt -> {
			double markup = KarmaEffects.markup(rt.npc().behaviour().karmaOf(player.getUuid()));
			if (markup <= 0) {
				return;
			}
			for (TradeOffer offer : ((VillagerEntity) (Object) this).getOffers()) {
				offer.increaseSpecialPrice(KarmaEffects.extraPrice(offer.getOriginalFirstBuyItem().getCount(), markup));
			}
		});
	}
}
