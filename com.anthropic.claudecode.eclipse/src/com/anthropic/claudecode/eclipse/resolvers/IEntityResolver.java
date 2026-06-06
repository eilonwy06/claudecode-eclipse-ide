package com.anthropic.claudecode.eclipse.resolvers;

/**
 * Recognizes one kind of actionable entity (a file path, a web link, …) inside a hovered or selected
 * text taken from a Claude Code answer, and turns it into something the user can open.
 */
public interface IEntityResolver {
	/**
	 * A recognized entity, decoupled from the text it came from: it already captured everything it
	 * needs to act, so invoking it later (e.g. on a user click) just opens the target.
	 */
	interface IResolvedEntity {
		/** Opens/reveals the entity. May hop to the SWT display thread; safe to call off it. */
		void locate();
	}

	/** Short human-readable name of this resolver (e.g. for menu labels). */
	String getName();

	/**
	 * Attempts to recognize an entity of this resolver's kind in {@code text}.
	 *
	 * @param text            the candidate text; a single token, or a larger chunk when
	 *                        {@code allowStripEdges} is {@code true}
	 * @param allowStripEdges when {@code true}, the resolver may trim surrounding junk (quotes,
	 *                        brackets, punctuation, prose) before matching; when {@code false} the
	 *                        text must be <em>exactly</em> the entity
	 * @return a resolved entity, or {@code null} if {@code text} holds no entity of this kind
	 */
	IResolvedEntity resolve(String text, boolean allowStripEdges);
}
