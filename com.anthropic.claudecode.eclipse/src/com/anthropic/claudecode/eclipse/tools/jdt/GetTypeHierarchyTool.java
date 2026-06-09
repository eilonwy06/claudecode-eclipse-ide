package com.anthropic.claudecode.eclipse.tools.jdt;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Gets type hierarchy information: supertypes, subtypes, and implementors.
 * Adapted from JDT Bridge's GraphHandler hierarchy methods.
 */
public class GetTypeHierarchyTool implements McpTool {

	@Override
	public String toolName() {
		return "getTypeHierarchy";
	}

	@Override
	public String description() {
		return "Get type hierarchy for a Java type: supertypes (extends/implements), "
				+ "direct subtypes, and all implementors. Input: fully qualified type name.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");

		JsonObject props = new JsonObject();

		JsonObject fqn = new JsonObject();
		fqn.addProperty("type", "string");
		fqn.addProperty("description", "Fully qualified name of the Java type");
		props.add("fqn", fqn);

		JsonObject mode = new JsonObject();
		mode.addProperty("type", "string");
		mode.addProperty("description", "What to return: 'supers', 'subtypes', 'implementors', or 'all' (default)");
		props.add("mode", mode);

		schema.add("properties", props);

		JsonArray required = new JsonArray();
		required.add("fqn");
		schema.add("required", required);

		return schema;
	}

	@Override
	public McpToolResult execute(JsonObject params) {
		try {
			String fqn = params.has("fqn") ? params.get("fqn").getAsString() : null;
			if (fqn == null || fqn.isBlank()) {
				return McpToolResult.error("Missing required parameter: fqn");
			}

			String mode = params.has("mode") ? params.get("mode").getAsString() : "all";

			IType type = JdtUtils.findType(fqn);
			if (type == null) {
				return McpToolResult.error("Could not find type: " + fqn);
			}

			JsonObject result = new JsonObject();
			result.addProperty("type", fqn);
			result.addProperty("isInterface", type.isInterface());
			result.addProperty("isClass", type.isClass());
			result.addProperty("isEnum", type.isEnum());

			boolean includeSupers = mode.equals("all") || mode.equals("supers");
			boolean includeSubtypes = mode.equals("all") || mode.equals("subtypes");
			boolean includeImplementors = mode.equals("all") || mode.equals("implementors");

			if (includeSupers) {
				ITypeHierarchy superHier = type.newSupertypeHierarchy(new NullProgressMonitor());
				JsonArray supers = new JsonArray();

				IType superclass = superHier.getSuperclass(type);
				if (superclass != null) {
					supers.add(typeSkeleton(superclass, "class"));
				}

				for (IType iface : superHier.getSuperInterfaces(type)) {
					supers.add(typeSkeleton(iface, "interface"));
				}

				result.add("supertypes", supers);
			}

			if (includeSubtypes || includeImplementors) {
				ITypeHierarchy hier = type.newTypeHierarchy(new NullProgressMonitor());

				if (includeSubtypes) {
					JsonArray subtypes = new JsonArray();
					for (IType sub : hier.getSubtypes(type)) {
						if (!sub.isAnonymous()) {
							subtypes.add(typeSkeleton(sub, getTypeKind(sub)));
						}
					}
					result.add("directSubtypes", subtypes);
				}

				if (includeImplementors) {
					JsonArray implementors = new JsonArray();
					for (IType sub : hier.getAllSubtypes(type)) {
						if (!sub.isAnonymous()) {
							implementors.add(typeSkeleton(sub, getTypeKind(sub)));
						}
					}
					result.add("allImplementors", implementors);
				}
			}

			return McpToolResult.success(result);
		} catch (Exception e) {
			return McpToolResult.error("Failed to get type hierarchy: " + e.getMessage());
		}
	}

	private JsonObject typeSkeleton(IType type, String kind) {
		JsonObject obj = new JsonObject();
		obj.addProperty("fqn", type.getFullyQualifiedName());
		obj.addProperty("name", type.getElementName());
		obj.addProperty("kind", kind);

		try {
			if (type.getResource() != null && type.getResource().getLocation() != null) {
				obj.addProperty("file", type.getResource().getLocation().toOSString());
			}
		} catch (Exception e) {
			// Skip file info
		}

		return obj;
	}

	private String getTypeKind(IType type) {
		try {
			if (type.isInterface()) return "interface";
			if (type.isEnum()) return "enum";
			if (type.isAnnotation()) return "annotation";
			if (type.isRecord()) return "record";
		} catch (Exception e) {
			// Fall through
		}
		return "class";
	}
}
