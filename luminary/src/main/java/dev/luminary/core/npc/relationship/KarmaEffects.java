package dev.luminary.core.npc.relationship;

/**
 * What a player's karma with the NPCs does outside the conversation, pure: the luck it grants or costs, the markup
 * traders put on their prices, and whether the player is worthy of the Trial of the Envoy. Karma is the NPCs' own
 * ledger (-100..100 per NPC, summed and clamped); nothing here reads the world.
 */
public final class KarmaEffects {
	/** Luck attribute points per 25 karma, capped at ±4 (a full Luck IV, or its mirror). */
	public static final double LUCK_PER_KARMA = 1.0 / 25.0;
	public static final double MAX_LUCK = 4.0;
	/** Traders' markup at karma -100: three quarters more; nothing at zero or above. */
	public static final double MAX_MARKUP = 0.75;
	/** Alms: this many emeralds to the NPC buy {@value #DONATION_KARMA} karma (the {@code DONATION} event). */
	public static final int DONATION_EMERALDS = 10;
	public static final int DONATION_KARMA = 5;

	private KarmaEffects() {
	}

	/** Karma summed over every NPC, clamped to one NPC's range. */
	public static double total(java.util.stream.DoubleStream perNpc) {
		return Math.max(-100, Math.min(100, perNpc.sum()));
	}

	/** The luck modifier: karma / 25, in half points, capped at ±4. */
	public static double luck(double karma) {
		double raw = Math.max(-MAX_LUCK, Math.min(MAX_LUCK, karma * LUCK_PER_KARMA));
		return Math.round(raw * 2) / 2.0;
	}

	/** Fraction added to every trader price: 0 at karma ≥ 0, up to {@value #MAX_MARKUP} at -100. */
	public static double markup(double karma) {
		return karma >= 0 ? 0 : Math.min(MAX_MARKUP, -karma / 100.0 * MAX_MARKUP);
	}

	/** Emeralds (or whatever) added to a price of {@code base} items under {@code markup}; never below one extra once there is any markup. */
	public static int extraPrice(int base, double markup) {
		return markup <= 0 ? 0 : Math.max(1, (int) Math.ceil(base * markup));
	}

	/** Worthy of the blade: no ill will on either side, and nothing owed. */
	public static boolean worthy(Relationship.Standing standing, double karma) {
		return standing != Relationship.Standing.HOSTILE && standing != Relationship.Standing.WARY && karma >= 0;
	}
}
