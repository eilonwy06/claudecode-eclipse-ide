package com.anthropic.claudecode.eclipse.resolvers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.runtime.Platform;

import com.anthropic.claudecode.eclipse.Activator;

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
		addResolverIfAvailable(JavaIdentifierEntityResolver::new,
				"org.eclipse.jdt.core", "org.eclipse.jdt.ui");
		addResolverIfAvailable(PythonIdentifierEntityResolver::new,
				"org.python.pydev", "org.python.pydev.core", "org.python.pydev.ast", "com.python.pydev.analysis");
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
	
	/**
	 * The display names of the registered resolvers, in registration order (e.g. for a hint that
	 * enumerates the recognizable entity kinds). Reflects the JDT-optional gating: the Java resolver's
	 * name is only present when {@link #addJavaResolverIfAvailable()} actually registered it.
	 *
	 * @see IEntityResolver#getName()
	 */
	public List<String> getResolverNames() {
		List<String> names = new ArrayList<>();
		for (IEntityResolver resolver : resolvers) {
			names.add(resolver.getName());
		}
		return names;
	}

	private void addResolver(IEntityResolver resolver) {
		resolvers.add(resolver);
	}

	/**
	 * Registers the resolver produced by {@code factory}, but only when every bundle in {@code bundleIds}
	 * is installed. The bundle checks gate the {@code factory.get()} call, so the resolver class is never
	 * loaded (and so never link-fails) on an Eclipse missing an optional dependency — this is what keeps
	 * those dependencies optional (declared {@code resolution:=optional} in {@code MANIFEST.MF}). The
	 * {@code catch} covers the rare case of a dependency being present but not wired to us (e.g. a version
	 * outside our range): the resolver is simply skipped rather than breaking the whole registry.
	 */
	private void addResolverIfAvailable(Supplier<IEntityResolver> factory, String... bundleIds) {
		for (String id : bundleIds) {
			if (Platform.getBundle(id) == null) return;
		}
		try {
			addResolver(factory.get());
		} catch (LinkageError | RuntimeException e) {
			Activator.logError("All the necessary bundles are present, but resolver could not be created; skipping", e);
		}
	}
}
