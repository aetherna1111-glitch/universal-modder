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
 * whose karma with the NPCs is -10 or below pays more (up to three quarters extra at -100, {@link KarmaEffects#markup})
 * and a player at +10 or above pays less (up to a third off at +100, {@link KarmaEffects#discount}, taken off what
 * vanilla's own discounts left). Villagers only (every villager-derived trader of other mods included); the wandering
 * trader keeps no per-customer prices and is left alone.
 */
@Mixin(VillagerEntity.class)
abstract class VillagerPricesMixin {
	@Inject(method = "prepareOffersFor", at = @At("TAIL"))
	private void luminary$karmaMarkup(PlayerEntity player, CallbackInfo ci) {
		LuminaryRuntime.get().ifPresent(rt -> {
			double karma = rt.npc().behaviour().karmaOf(player.getUuid());
			double markup = KarmaEffects.markup(karma);
			double discount = KarmaEffects.discount(karma);
			VillagerEntity self = (VillagerEntity) (Object) this;
			StringBuilder before = new StringBuilder();
			for (TradeOffer offer : self.getOffers()) {
				before.append(before.length() == 0 ? "" : ", ").append(offer.getOriginalFirstBuyItem().getCount()).append(offer.getSpecialPrice() >= 0 ? "+" : "").append(offer.getSpecialPrice());
			}
			if (markup <= 0 && discount <= 0) {
				dev.luminary.fabric.Luminary.LOGGER.info("[luminary] villager prices for {}: karma {} → unchanged; vanilla special prices (base+special): {}", player.getGameProfile().getName(), (int) karma, before);
				return;
			}
			for (TradeOffer offer : self.getOffers()) {
				int base = offer.getOriginalFirstBuyItem().getCount();
				if (markup > 0) {
					offer.increaseSpecialPrice(KarmaEffects.extraPrice(base, markup));
				} else {
					offer.increaseSpecialPrice(-KarmaEffects.priceOff(base + Math.min(0, offer.getSpecialPrice()), discount));   // off what the player would pay after vanilla's own discounts
				}
			}
			dev.luminary.fabric.Luminary.LOGGER.info("[luminary] villager prices for {}: karma {} → {}{}%; before: {}", player.getGameProfile().getName(), (int) karma,
					markup > 0 ? "+" : "-", (int) Math.round((markup > 0 ? markup : discount) * 100), before);
		});
	}
}
