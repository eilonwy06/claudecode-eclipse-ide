package com.anthropic.claudecode.eclipse.tools.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Shared utilities for JDT operations. Adapted from JDT Bridge's JdtUtils.java.
 */
public final class JdtUtils {

	private static final Pattern LAMBDA_SUFFIX = Pattern.compile("\\(\\) -> \\{\\.\\.\\.\\}$");
	private static final Pattern ANON_SUFFIX = Pattern.compile("new\\.\\.\\.\\(\\) \\{\\.\\.\\.\\}$");
	private static final Pattern METHOD_PATTERN = Pattern.compile("^(.+)\\.([^.]+)\\(([^)]*)\\)$");
	private static final Pattern FIELD_PATTERN = Pattern.compile("^(.+)\\.([^.(]+)$");

	private JdtUtils() {}

	/**
	 * Resolves an FQN to its IJavaElement across all Java projects.
	 * Supports types, methods (with params), and fields.
	 */
	public static IJavaElement resolveElement(String fqn) {
		if (fqn == null || fqn.isBlank()) {
			return null;
		}

		fqn = fqn.trim();

		// Check for synthetic suffixes (lambdas, anonymous classes)
		if (LAMBDA_SUFFIX.matcher(fqn).find() || ANON_SUFFIX.matcher(fqn).find()) {
			return null; // Not supported yet
		}

		// Try as method: com.example.Foo.bar(String,int)
		Matcher methodMatcher = METHOD_PATTERN.matcher(fqn);
		if (methodMatcher.matches()) {
			String typeFqn = methodMatcher.group(1);
			String methodName = methodMatcher.group(2);
			String paramsStr = methodMatcher.group(3);
			return findMethod(typeFqn, methodName, paramsStr);
		}

		// Try as type first
		IType type = findType(fqn);
		if (type != null) {
			return type;
		}

		// Try as field: com.example.Foo.FIELD
		Matcher fieldMatcher = FIELD_PATTERN.matcher(fqn);
		if (fieldMatcher.matches()) {
			String typeFqn = fieldMatcher.group(1);
			String fieldName = fieldMatcher.group(2);
			return findField(typeFqn, fieldName);
		}

		return null;
	}

	/**
	 * Finds a type by FQN across all Java projects.
	 */
	public static IType findType(String fqn) {
		for (IJavaProject jp : getJavaProjects()) {
			try {
				IType type = jp.findType(fqn);
				if (type != null && type.exists()) {
					return type;
				}
			} catch (JavaModelException e) {
				// Continue to next project
			}
		}
		return null;
	}

	/**
	 * Finds a method by type FQN, method name, and parameter types.
	 */
	public static IMethod findMethod(String typeFqn, String methodName, String paramsStr) {
		IType type = findType(typeFqn);
		if (type == null) {
			return null;
		}

		String[] paramTypes = parseParamTypes(paramsStr);
		try {
			for (IMethod method : type.getMethods()) {
				if (!method.getElementName().equals(methodName)) {
					continue;
				}
				if (typeMatches(method.getParameterTypes(), paramTypes)) {
					return method;
				}
			}
		} catch (JavaModelException e) {
			// Fall through
		}
		return null;
	}

	/**
	 * Finds a field by type FQN and field name.
	 */
	public static IField findField(String typeFqn, String fieldName) {
		IType type = findType(typeFqn);
		if (type == null) {
			return null;
		}
		try {
			IField field = type.getField(fieldName);
			if (field != null && field.exists()) {
				return field;
			}
		} catch (Exception e) {
			// Fall through
		}
		return null;
	}

	/**
	 * Parses a comma-separated parameter list, respecting generics.
	 * "Map<String,Integer>,int" → ["Map<String,Integer>", "int"]
	 */
	public static String[] parseParamTypes(String paramsStr) {
		if (paramsStr == null || paramsStr.isBlank()) {
			return new String[0];
		}

		List<String> params = new ArrayList<>();
		int depth = 0;
		StringBuilder current = new StringBuilder();

		for (char c : paramsStr.toCharArray()) {
			if (c == '<') {
				depth++;
				current.append(c);
			} else if (c == '>') {
				depth--;
				current.append(c);
			} else if (c == ',' && depth == 0) {
				params.add(current.toString().trim());
				current = new StringBuilder();
			} else {
				current.append(c);
			}
		}
		if (current.length() > 0) {
			params.add(current.toString().trim());
		}

		return params.toArray(new String[0]);
	}

	/**
	 * Checks if JDT parameter signatures match the given type names.
	 */
	public static boolean typeMatches(String[] jdtSigs, String[] typeNames) {
		if (jdtSigs.length != typeNames.length) {
			return false;
		}
		for (int i = 0; i < jdtSigs.length; i++) {
			String resolved = Signature.toString(jdtSigs[i]);
			String expected = stripGenerics(typeNames[i]);
			String resolvedSimple = stripGenerics(simpleName(resolved));
			String expectedSimple = stripGenerics(simpleName(expected));
			if (!resolvedSimple.equals(expectedSimple) && !resolved.equals(expected)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Strips generic parameters: "Map<K,V>" → "Map"
	 */
	public static String stripGenerics(String type) {
		int idx = type.indexOf('<');
		return idx < 0 ? type : type.substring(0, idx);
	}

	/**
	 * Extracts simple name: "java.util.Map" → "Map"
	 */
	public static String simpleName(String fqn) {
		int idx = fqn.lastIndexOf('.');
		return idx < 0 ? fqn : fqn.substring(idx + 1);
	}

	/**
	 * Returns all Java projects in the workspace.
	 */
	public static List<IJavaProject> getJavaProjects() {
		List<IJavaProject> result = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isOpen()) {
				continue;
			}
			try {
				if (project.hasNature(JavaCore.NATURE_ID)) {
					result.add(JavaCore.create(project));
				}
			} catch (Exception e) {
				// Skip
			}
		}
		return result;
	}

	/**
	 * Gets the Java project for a given resource.
	 */
	public static IJavaProject getJavaProject(IResource resource) {
		if (resource == null) {
			return null;
		}
		IProject project = resource.getProject();
		if (project == null || !project.isOpen()) {
			return null;
		}
		try {
			if (project.hasNature(JavaCore.NATURE_ID)) {
				return JavaCore.create(project);
			}
		} catch (Exception e) {
			// Fall through
		}
		return null;
	}

	/**
	 * Finds all implementations of a method (overrides in subtypes).
	 */
	public static List<IMethod> findImplementations(IMethod method) throws JavaModelException {
		List<IMethod> result = new ArrayList<>();
		IType declaringType = method.getDeclaringType();
		if (declaringType == null) {
			return result;
		}

		ITypeHierarchy hierarchy = declaringType.newTypeHierarchy(new NullProgressMonitor());
		for (IType subtype : hierarchy.getAllSubtypes(declaringType)) {
			for (IMethod m : subtype.getMethods()) {
				if (m.getElementName().equals(method.getElementName())
						&& typeMatches(m.getParameterTypes(), extractTypeNames(method.getParameterTypes()))) {
					result.add(m);
				}
			}
		}
		return result;
	}

	private static String[] extractTypeNames(String[] sigs) {
		String[] names = new String[sigs.length];
		for (int i = 0; i < sigs.length; i++) {
			names[i] = Signature.toString(sigs[i]);
		}
		return names;
	}

	/**
	 * Returns the FQN of a Java element.
	 */
	public static String getFqn(IJavaElement element) {
		if (element instanceof IType type) {
			return type.getFullyQualifiedName();
		} else if (element instanceof IMethod method) {
			IType type = method.getDeclaringType();
			StringBuilder sb = new StringBuilder(type.getFullyQualifiedName());
			sb.append('.').append(method.getElementName()).append('(');
			try {
				String[] params = method.getParameterTypes();
				for (int i = 0; i < params.length; i++) {
					if (i > 0) sb.append(',');
					sb.append(Signature.toString(params[i]));
				}
			} catch (Exception e) {
				// Ignore
			}
			sb.append(')');
			return sb.toString();
		} else if (element instanceof IField field) {
			IType type = field.getDeclaringType();
			return type.getFullyQualifiedName() + "." + field.getElementName();
		} else if (element instanceof IPackageFragment pkg) {
			return pkg.getElementName();
		}
		return element.getElementName();
	}
}
