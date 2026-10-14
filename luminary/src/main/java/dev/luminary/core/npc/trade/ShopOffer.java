package dev.luminary.core.npc.trade;

import java.util.Objects;

/** One thing the NPC sells for its points: an item id, how many per purchase, the price. From the NPC definition's {@code shop} list. */
public record ShopOffer(String itemId, int count, int price, boolean unique) {
	public ShopOffer {
		Objects.requireNonNull(itemId);
		if (count < 1 || price < 0) {
			throw new IllegalArgumentException("bad offer " + itemId);
		}
	}
}
