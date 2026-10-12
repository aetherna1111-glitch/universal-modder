package dev.luminary.core.catalog;

/** Who may read a record once it is VERIFIED. OBSERVED records are always visible to their observer and admins. */
public enum Visibility {
	PUBLIC_WHEN_VERIFIED,
	/** Visible only to holders of a role (e.g. Luminarium members); role checks live outside the core. */
	RESTRICTED
}
