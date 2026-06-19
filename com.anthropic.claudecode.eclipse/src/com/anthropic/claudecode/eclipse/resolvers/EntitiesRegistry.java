package com.anthropic.claudecode.eclipse.resolvers;

import java.util.ArrayList;
import java.util.List;

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
		addResolverIfAvailable("JavaIdentifierEntityResolver",
				"org.eclipse.jdt.core", "org.eclipse.jdt.ui");
		addResolverIfAvailable("PythonIdentifierEntityResolver",
				"org.python.pydev", "org.python.pydev.core", "org.python.pydev.ast", "com.python.pydev.analysis");
		addResolverIfAvailable("CppIdentifierEntityResolver",
				"org.eclipse.cdt.core", "org.eclipse.cdt.ui");
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
	 * enumerates the recognizable entity kinds). Reflects the optional gating: an optional resolver's
	 * name is only present when {@link #addResolverIfAvailable(String, String...)} actually registered it.
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
	 * Registers the resolver named (by simple class name, in this package) by {@code resolverClassName}, but
	 * only when every bundle in {@code bundleIds} is installed. This is what keeps those dependencies optional
	 * (declared {@code resolution:=optional} in {@code MANIFEST.MF}): the bundle checks gate the reflective
	 * {@link Class#forName(String) Class.forName}, so on an Eclipse missing an optional dependency the resolver
	 * class is never loaded and so never link-fails.
	 *
	 * <p><b>Why a class-name string, not a {@code Supplier}/{@code Resolver::new} method reference.</b> A
	 * compile-time {@code Resolver::new} puts a {@code MethodHandle} to the resolver's constructor in <em>this</em>
	 * class's constant pool; executing that {@code invokedynamic} (which happens while evaluating the argument,
	 * <em>before</em> this method runs) eagerly loads <em>and verifies</em> the resolver. Verification loads the
	 * exception types in the resolver's {@code catch} clauses — e.g. {@code MisconfigurationException} — so on an
	 * Eclipse missing the optional bundle it throws {@link NoClassDefFoundError} <em>past</em> the guard below.
	 * Naming the class by string keeps the only reference to it a plain {@code String}, so it is first touched by
	 * {@code Class.forName} — inside the guard. <b>Do not reintroduce a method reference here.</b>
	 *
	 * <p>The {@code catch} covers the rare case of a dependency being present but not wired to us (e.g. a version
	 * outside our range): the resolver is simply skipped rather than breaking the whole registry.
	 */
	private void addResolverIfAvailable(String resolverClassName, String... bundleIds) {
		for (String id : bundleIds) {
			if (Platform.getBundle(id) == null) return;
		}
		try {
			Class<?> clazz = Class.forName(EntitiesRegistry.class.getPackageName() + "." + resolverClassName);
			addResolver((IEntityResolver) clazz.getDeclaredConstructor().newInstance());
		} catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
			Activator.logError("All the necessary bundles are present, but " + resolverClassName + 
					" could not be created; skipping", e);
		}
	}
}
