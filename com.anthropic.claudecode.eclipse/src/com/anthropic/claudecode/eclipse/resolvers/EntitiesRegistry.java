package com.anthropic.claudecode.eclipse.resolvers;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the set of {@link IEntityResolver}s and runs a piece of text through all of them, collecting
 * every actionable entity recognized in it. This is the single entry point the UI uses to turn hovered
 * or selected Claude Code answer text into things the user can open.
 */
public class EntitiesRegistry {
	/** An entity recognized by a resolver, tagged with that resolver's {@link IEntityResolver#getName() name}. */
	public record NamedResolvedEntity(String resolverName, IEntityResolver.IResolvedEntity entity) {}

	private final List<IEntityResolver> resolvers = new ArrayList<>();

	public EntitiesRegistry() {
		addResolver(new WebLinkEntityResolver());
		addResolver(new FileEntityResolver());
	}

	/**
	 * Runs {@code text} through every registered resolver and returns each entity that was recognized,
	 * tagged with the name of the resolver that produced it. Resolvers that find nothing are skipped, so
	 * the result holds one {@link NamedResolvedEntity} per successful match (possibly empty, never
	 * {@code null}).
	 *
	 * @param text            the candidate text to inspect
	 * @param allowStripEdges when {@code true}, resolvers may trim surrounding junk before matching; when
	 *                        {@code false} the text must be <em>exactly</em> the entity
	 * @return the recognized entities, in resolver registration order
	 * @see IEntityResolver#resolve(String, boolean)
	 */
	public List<NamedResolvedEntity> resolve(String text, boolean allowStripEdges) {
		List<NamedResolvedEntity> result = new ArrayList<>();
		for (IEntityResolver resolver : resolvers) {
			IEntityResolver.IResolvedEntity entity = resolver.resolve(text, allowStripEdges);
			if (entity != null) {
				result.add(new NamedResolvedEntity(resolver.getName(), entity));
			}
		}
		return result;
	}
	
	private void addResolver(IEntityResolver resolver) {
		resolvers.add(resolver);
	}
}
