package net.bluemind.devtools.testrunner.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

public final class BmMcpTools {

	public static final String TOOL_RUN_BUNDLE = "run_bundle_tests";
	public static final String TOOL_RUN_CLASS = "run_class_tests";
	public static final String TOOL_RUN_METHOD = "run_test_method";

	private BmMcpTools() {
	}

	public static List<Map<String, Object>> descriptors() {
		return List.of(
				toolDescriptor(TOOL_RUN_BUNDLE,
						"Run all JUnit Plugin Tests of a BlueMind bundle (*.tests project).",
						Map.of(
								"project", paramString("Eclipse project name, typically ends with '.tests'."),
								"mode", paramMode()),
						List.of("project")),
				toolDescriptor(TOOL_RUN_CLASS,
						"Run all @Test methods of a single test class.",
						Map.of(
								"project", paramString("Eclipse project name containing the class."),
								"className", paramString(
										"Fully qualified test class name, e.g. net.bluemind.foo.tests.MyTest."),
								"mode", paramMode()),
						List.of("project", "className")),
				toolDescriptor(TOOL_RUN_METHOD,
						"Run a single @Test method.",
						Map.of(
								"project", paramString("Eclipse project name containing the class."),
								"className",
								paramString("Fully qualified test class name."),
								"methodName", paramString("Test method name (no parentheses)."),
								"mode", paramMode()),
						List.of("project", "className", "methodName")));
	}

	public static CompletableFuture<TestRunResult> invoke(String tool, Map<String, Object> args, long timeoutMs)
			throws CoreException {
		String mode = str(args, "mode");
		if (mode == null || mode.isBlank()) {
			mode = "run";
		}
		switch (tool) {
		case TOOL_RUN_BUNDLE: {
			String projectName = required(args, "project");
			IJavaProject jp = findJavaProject(projectName);
			return BmMcpLauncher.instance().runProject(jp, mode, timeoutMs);
		}
		case TOOL_RUN_CLASS: {
			String projectName = required(args, "project");
			String className = required(args, "className");
			IType type = findType(findJavaProject(projectName), className);
			return BmMcpLauncher.instance().runType(type, mode, timeoutMs);
		}
		case TOOL_RUN_METHOD: {
			String projectName = required(args, "project");
			String className = required(args, "className");
			String methodName = required(args, "methodName");
			IType type = findType(findJavaProject(projectName), className);
			IMethod method = findMethod(type, methodName);
			return BmMcpLauncher.instance().runMethod(method, mode, timeoutMs);
		}
		default:
			throw new IllegalArgumentException("Unknown tool: " + tool);
		}
	}

	private static IJavaProject findJavaProject(String name) {
		IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		if (!p.exists()) {
			throw new IllegalArgumentException("Project not found in workspace: " + name);
		}
		if (!p.isOpen()) {
			throw new IllegalArgumentException("Project is closed: " + name);
		}
		IJavaProject jp = JavaCore.create(p);
		if (jp == null || !jp.exists()) {
			throw new IllegalArgumentException("Project is not a Java project: " + name);
		}
		return jp;
	}

	private static IType findType(IJavaProject jp, String fqcn) throws CoreException {
		IType t = jp.findType(fqcn);
		if (t == null || !t.exists()) {
			throw new IllegalArgumentException(
					"Class not found in project " + jp.getElementName() + ": " + fqcn);
		}
		return t;
	}

	private static IMethod findMethod(IType type, String methodName) throws CoreException {
		for (IMethod m : type.getMethods()) {
			if (m.getElementName().equals(methodName)) {
				return m;
			}
		}
		throw new IllegalArgumentException(
				"Method " + methodName + " not found on " + type.getFullyQualifiedName());
	}

	private static String required(Map<String, Object> args, String key) {
		String v = str(args, key);
		if (v == null || v.isBlank()) {
			throw new IllegalArgumentException("Missing required argument: " + key);
		}
		return v;
	}

	private static String str(Map<String, Object> args, String key) {
		Object v = args == null ? null : args.get(key);
		return v == null ? null : v.toString();
	}

	private static Map<String, Object> toolDescriptor(String name, String description,
			Map<String, Map<String, Object>> properties, List<String> required) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", required);
		Map<String, Object> tool = new LinkedHashMap<>();
		tool.put("name", name);
		tool.put("description", description);
		tool.put("inputSchema", schema);
		return tool;
	}

	private static Map<String, Object> paramString(String desc) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "string");
		m.put("description", desc);
		return m;
	}

	private static Map<String, Object> paramMode() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "string");
		m.put("description", "Launch mode, 'run' (default) or 'debug'.");
		m.put("enum", List.of("run", "debug"));
		return m;
	}
}
