package com.anthropic.claudecode.eclipse.tools.jdt;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Finds all references to a Java element (type, method, or field) in the workspace.
 * Adapted from JDT Bridge's reference search functionality.
 */
public class FindReferencesTool implements McpTool {

	@Override
	public String toolName() {
		return "findReferences";
	}

	@Override
	public String description() {
		return "Find all references to a Java type, method, or field in the workspace. "
				+ "Input: fully qualified name (e.g., 'com.example.Foo' for type, "
				+ "'com.example.Foo.bar(String,int)' for method, 'com.example.Foo.FIELD' for field).";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");

		JsonObject props = new JsonObject();

		JsonObject fqn = new JsonObject();
		fqn.addProperty("type", "string");
		fqn.addProperty("description", "Fully qualified name of the Java element to find references for");
		props.add("fqn", fqn);

		JsonObject limit = new JsonObject();
		limit.addProperty("type", "integer");
		limit.addProperty("description", "Maximum number of references to return (default: 100)");
		props.add("limit", limit);

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

			int limit = params.has("limit") ? params.get("limit").getAsInt() : 100;

			IJavaElement element = JdtUtils.resolveElement(fqn);
			if (element == null) {
				return McpToolResult.error("Could not resolve element: " + fqn);
			}

			int searchFor = getSearchFor(element);
			if (searchFor < 0) {
				return McpToolResult.error("Unsupported element type for reference search");
			}

			SearchPattern pattern = SearchPattern.createPattern(
					element,
					IJavaSearchConstants.REFERENCES);

			if (pattern == null) {
				return McpToolResult.error("Could not create search pattern for: " + fqn);
			}

			List<JsonObject> references = new ArrayList<>();
			SearchEngine engine = new SearchEngine();
			IJavaSearchScope scope = SearchEngine.createWorkspaceScope();

			SearchRequestor requestor = new SearchRequestor() {
				@Override
				public void acceptSearchMatch(SearchMatch match) {
					if (references.size() >= limit) {
						return;
					}

					JsonObject ref = new JsonObject();

					if (match.getResource() != null && match.getResource().getLocation() != null) {
						ref.addProperty("file", match.getResource().getLocation().toOSString());
					}
					ref.addProperty("offset", match.getOffset());
					ref.addProperty("length", match.getLength());
					ref.addProperty("accuracy", match.getAccuracy() == SearchMatch.A_ACCURATE ? "exact" : "potential");

					if (match.getElement() instanceof IJavaElement je) {
						ref.addProperty("enclosingElement", JdtUtils.getFqn(je));
					}

					references.add(ref);
				}
			};

			engine.search(
					pattern,
					new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
					scope,
					requestor,
					new NullProgressMonitor());

			JsonObject result = new JsonObject();
			result.addProperty("element", fqn);
			result.addProperty("elementType", getElementTypeName(element));
			result.addProperty("count", references.size());
			result.addProperty("truncated", references.size() >= limit);

			JsonArray refsArray = new JsonArray();
			for (JsonObject ref : references) {
				refsArray.add(ref);
			}
			result.add("references", refsArray);

			return McpToolResult.success(result);
		} catch (Exception e) {
			return McpToolResult.error("Failed to find references: " + e.getMessage());
		}
	}

	private int getSearchFor(IJavaElement element) {
		if (element instanceof IType) {
			return IJavaSearchConstants.TYPE;
		} else if (element instanceof IMethod) {
			return IJavaSearchConstants.METHOD;
		} else if (element instanceof IField) {
			return IJavaSearchConstants.FIELD;
		}
		return -1;
	}

	private String getElementTypeName(IJavaElement element) {
		if (element instanceof IType) {
			return "type";
		} else if (element instanceof IMethod) {
			return "method";
		} else if (element instanceof IField) {
			return "field";
		}
		return "unknown";
	}
}
