package com.anthropic.claudecode.eclipse.tools.jdt;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Gets detailed information about a Java symbol (type, method, or field).
 * Adapted from JDT Bridge's GraphHandler type/method/field handlers.
 */
public class GetSymbolInfoTool implements McpTool {

	@Override
	public String toolName() {
		return "getSymbolInfo";
	}

	@Override
	public String description() {
		return "Get detailed information about a Java type, method, or field: "
				+ "modifiers, signature, source location, members. "
				+ "Input: fully qualified name.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");

		JsonObject props = new JsonObject();

		JsonObject fqn = new JsonObject();
		fqn.addProperty("type", "string");
		fqn.addProperty("description", "Fully qualified name of the Java element");
		props.add("fqn", fqn);

		JsonObject includeMembers = new JsonObject();
		includeMembers.addProperty("type", "boolean");
		includeMembers.addProperty("description", "Include type members (methods/fields) in response");
		props.add("includeMembers", includeMembers);

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

			boolean includeMembers = params.has("includeMembers") && params.get("includeMembers").getAsBoolean();

			IJavaElement element = JdtUtils.resolveElement(fqn);
			if (element == null) {
				return McpToolResult.error("Could not resolve element: " + fqn);
			}

			JsonObject result = new JsonObject();
			result.addProperty("fqn", fqn);

			if (element instanceof IType type) {
				populateTypeInfo(result, type, includeMembers);
			} else if (element instanceof IMethod method) {
				populateMethodInfo(result, method);
			} else if (element instanceof IField field) {
				populateFieldInfo(result, field);
			} else {
				return McpToolResult.error("Unsupported element type: " + element.getClass().getSimpleName());
			}

			return McpToolResult.success(result);
		} catch (Exception e) {
			return McpToolResult.error("Failed to get symbol info: " + e.getMessage());
		}
	}

	private void populateTypeInfo(JsonObject result, IType type, boolean includeMembers) throws Exception {
		result.addProperty("elementType", "type");
		result.addProperty("name", type.getElementName());
		result.addProperty("kind", getTypeKind(type));
		result.addProperty("modifiers", Flags.toString(type.getFlags()));

		if (type.getSuperclassName() != null) {
			result.addProperty("superclass", type.getSuperclassName());
		}

		String[] interfaces = type.getSuperInterfaceNames();
		if (interfaces.length > 0) {
			JsonArray arr = new JsonArray();
			for (String iface : interfaces) {
				arr.add(iface);
			}
			result.add("interfaces", arr);
		}

		addSourceInfo(result, type);

		if (includeMembers) {
			JsonArray methods = new JsonArray();
			for (IMethod m : type.getMethods()) {
				JsonObject mj = new JsonObject();
				mj.addProperty("name", m.getElementName());
				mj.addProperty("signature", getMethodSignature(m));
				mj.addProperty("modifiers", Flags.toString(m.getFlags()));
				methods.add(mj);
			}
			result.add("methods", methods);

			JsonArray fields = new JsonArray();
			for (IField f : type.getFields()) {
				JsonObject fj = new JsonObject();
				fj.addProperty("name", f.getElementName());
				fj.addProperty("type", Signature.toString(f.getTypeSignature()));
				fj.addProperty("modifiers", Flags.toString(f.getFlags()));
				fields.add(fj);
			}
			result.add("fields", fields);
		}
	}

	private void populateMethodInfo(JsonObject result, IMethod method) throws Exception {
		result.addProperty("elementType", "method");
		result.addProperty("name", method.getElementName());
		result.addProperty("signature", getMethodSignature(method));
		result.addProperty("modifiers", Flags.toString(method.getFlags()));
		result.addProperty("returnType", Signature.toString(method.getReturnType()));
		result.addProperty("isConstructor", method.isConstructor());

		String[] paramNames = method.getParameterNames();
		String[] paramTypes = method.getParameterTypes();
		JsonArray params = new JsonArray();
		for (int i = 0; i < paramTypes.length; i++) {
			JsonObject p = new JsonObject();
			p.addProperty("name", i < paramNames.length ? paramNames[i] : "arg" + i);
			p.addProperty("type", Signature.toString(paramTypes[i]));
			params.add(p);
		}
		result.add("parameters", params);

		String[] exceptions = method.getExceptionTypes();
		if (exceptions.length > 0) {
			JsonArray exc = new JsonArray();
			for (String e : exceptions) {
				exc.add(Signature.toString(e));
			}
			result.add("exceptions", exc);
		}

		result.addProperty("declaringType", method.getDeclaringType().getFullyQualifiedName());
		addSourceInfo(result, method);
	}

	private void populateFieldInfo(JsonObject result, IField field) throws Exception {
		result.addProperty("elementType", "field");
		result.addProperty("name", field.getElementName());
		result.addProperty("type", Signature.toString(field.getTypeSignature()));
		result.addProperty("modifiers", Flags.toString(field.getFlags()));
		result.addProperty("isEnumConstant", field.isEnumConstant());

		int flags = field.getFlags();
		if (Flags.isStatic(flags) && Flags.isFinal(flags)) {
			Object constant = field.getConstant();
			if (constant != null) {
				result.addProperty("constantValue", constant.toString());
			}
		}

		result.addProperty("declaringType", field.getDeclaringType().getFullyQualifiedName());
		addSourceInfo(result, field);
	}

	private void addSourceInfo(JsonObject result, IJavaElement element) {
		try {
			if (element.getResource() != null && element.getResource().getLocation() != null) {
				result.addProperty("file", element.getResource().getLocation().toOSString());
			}

			if (element instanceof org.eclipse.jdt.core.ISourceReference sr) {
				ISourceRange range = sr.getSourceRange();
				if (range != null) {
					result.addProperty("offset", range.getOffset());
					result.addProperty("length", range.getLength());
				}
				ISourceRange nameRange = sr.getNameRange();
				if (nameRange != null) {
					result.addProperty("nameOffset", nameRange.getOffset());
					result.addProperty("nameLength", nameRange.getLength());
				}
			}
		} catch (Exception e) {
			// Skip source info
		}
	}

	private String getMethodSignature(IMethod method) throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append(method.getElementName()).append('(');
		String[] params = method.getParameterTypes();
		for (int i = 0; i < params.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(Signature.toString(params[i]));
		}
		sb.append(')');
		return sb.toString();
	}

	private String getTypeKind(IType type) throws Exception {
		if (type.isInterface()) return "interface";
		if (type.isEnum()) return "enum";
		if (type.isAnnotation()) return "annotation";
		if (type.isRecord()) return "record";
		return "class";
	}
}
