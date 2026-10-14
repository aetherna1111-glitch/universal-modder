package dev.luminary.core.npc.conversation;

/**
 * What a line of chat is about, when it is about something the NPC can answer without a model. {@code ACTION} topics
 * are carried out; {@code FACTUAL} topics are answered from authoritative numbers (points, quests, karma, gear) with an
 * authored line; {@code SOCIAL} topics go to the model when one is ready, else to an authored line.
 */
public enum Topic {
	FOLLOW_ME(Kind.ACTION), STOP(Kind.ACTION),
	SHOP(Kind.FACTUAL), QUESTS(Kind.FACTUAL), POINTS(Kind.FACTUAL), KARMA(Kind.FACTUAL), SWORD(Kind.FACTUAL), HARP(Kind.FACTUAL),
	ARMOUR(Kind.FACTUAL), HELP(Kind.FACTUAL), DONATE(Kind.FACTUAL), REMEMBER_ME(Kind.FACTUAL),
	GREETING(Kind.SOCIAL), FAREWELL(Kind.SOCIAL), THANKS(Kind.SOCIAL), HOW_ARE_YOU(Kind.SOCIAL), WHO_ARE_YOU(Kind.SOCIAL),
	WHAT_DO_YOU_DO(Kind.SOCIAL), WHERE(Kind.SOCIAL), TIME(Kind.SOCIAL), JOKE(Kind.SOCIAL), COMPLIMENT(Kind.SOCIAL), INSULT(Kind.SOCIAL),
	YES(Kind.SOCIAL), NO(Kind.SOCIAL), LORE(Kind.SOCIAL), WEATHER(Kind.SOCIAL);

	public enum Kind { ACTION, FACTUAL, SOCIAL }

	private final Kind kind;

	Topic(Kind kind) {
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	/** The authored line group answering this topic: {@code topic.<name>}. */
	public String lineGroup() {
		return "topic." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
