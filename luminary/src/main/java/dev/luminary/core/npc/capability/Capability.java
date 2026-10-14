package dev.luminary.core.npc.capability;

/**
 * Everything the NPC can do, as a closed list. {@code modelMayRequest} marks the subset a language model is allowed
 * to ask for: the model proposes among those, deterministic validators decide, the server executes. Everything else
 * (fighting, moving the unique weapon, touching the story, progression or the world outside its task) is reachable
 * only from authoritative events, admin commands or the one quest integration point.
 */
public enum Capability {
	SPEAK(true, "say one bounded line in chat"),
	REMEMBER_NOTE(true, "store a low-trust note about the player"),
	REVEAL_ALLOWED_CLUE(true, "weave one clue the story already allows into a line"),
	GATHER_WOOD(true, "cut logs the scanner saw and bring them to an authorized container"),
	DEPOSIT(true, "put carried cargo into an authorized container"),
	WITHDRAW(true, "take items from an authorized container"),
	SMELT(true, "run a nearby furnace with items from an authorized container"),
	MOVE_TO(true, "walk to the player or a seen position"),
	USE_CONTAINER(true, "accept a container the player hands over while looking at it (permission-gated)"),
	FORGET_CONTAINER(true, "give a container back"),
	DEFEND(false, "raise the shield, warn, fight back"),
	WEAPON_TRANSFER(false, "permanently hand over the unique weapon"),
	STORY_WRITE(false, "change story phase, branch or clues"),
	PROGRESSION_WRITE(false, "unlock technologies, signals, eras"),
	WORLD_EDIT(false, "place or break blocks outside a validated task"),
	TELEPORT(false, "move without walking"),
	ADMIN(false, "commands, config, persistence");

	private final boolean modelMayRequest;
	private final String description;

	Capability(boolean modelMayRequest, String description) {
		this.modelMayRequest = modelMayRequest;
		this.description = description;
	}

	public boolean modelMayRequest() {
		return modelMayRequest;
	}

	public String description() {
		return description;
	}
}
