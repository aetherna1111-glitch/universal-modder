package dev.luminary.core.npc.combat;

import java.time.Duration;

/**
 * Configurable thresholds of self-defence. {@code attacksBeforeCombat} (3..5 by design) hits inside
 * {@code attackWindow} escalate from warning to combat; an attacker who stays beyond {@code deescalateDistance}
 * for {@code deescalateAfter}, or apologizes, is let go; after a fight the NPC recovers for {@code recovery}.
 * {@code weaponDamageFraction} of a player's maximum health is the unique weapon's hit; {@code holdGroundRadius}
 * is how far from where the fight started the NPC will step while fighting (no pursuit beyond it). The raised
 * shield absorbs {@code shieldPoints} of damage before it breaks; a broken shield is back after {@code shieldRepair}.
 */
public record CombatRules(int attacksBeforeCombat, Duration attackWindow, double deescalateDistance, Duration deescalateAfter,
						  Duration recovery, double weaponDamageFraction, double strikeReach, double holdGroundRadius,
						  Duration knockout, Duration helpShortensBy, Duration duelConsentWindow, boolean shieldFirst,
						  double shieldPoints, Duration shieldRepair) {
	public static final CombatRules DEFAULTS = new CombatRules(5, Duration.ofSeconds(60), 8.0, Duration.ofSeconds(20),
			Duration.ofSeconds(30), 0.5, 3.5, 10.0, Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(90), true, 20.0, Duration.ofMinutes(5));

	/** Without the shield fields: default 20 shield points, five minutes of repair. */
	public CombatRules(int attacksBeforeCombat, Duration attackWindow, double deescalateDistance, Duration deescalateAfter, Duration recovery,
					   double weaponDamageFraction, double strikeReach, double holdGroundRadius, Duration knockout, Duration helpShortensBy,
					   Duration duelConsentWindow, boolean shieldFirst) {
		this(attacksBeforeCombat, attackWindow, deescalateDistance, deescalateAfter, recovery, weaponDamageFraction, strikeReach, holdGroundRadius,
				knockout, helpShortensBy, duelConsentWindow, shieldFirst, 20.0, Duration.ofMinutes(5));
	}

	public CombatRules {
		if (attacksBeforeCombat < 1 || attackWindow.isNegative() || deescalateDistance < 0 || deescalateAfter.isNegative()
				|| recovery.isNegative() || weaponDamageFraction <= 0 || weaponDamageFraction > 1 || strikeReach <= 0 || holdGroundRadius < 0
				|| knockout.isNegative() || helpShortensBy.isNegative() || duelConsentWindow.isNegative() || shieldPoints <= 0 || shieldRepair.isNegative()) {
			throw new IllegalArgumentException("invalid combat rules");
		}
	}
}
