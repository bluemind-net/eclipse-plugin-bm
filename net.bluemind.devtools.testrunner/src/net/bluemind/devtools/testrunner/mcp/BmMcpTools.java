package net.bluemind.devtools.testrunner.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

public final class BmMcpTools {

	public static final String TOOL_RUN_BUNDLE = "run_bundle_tests";
	public static final String TOOL_RUN_CLASS = "run_class_tests";
	public static final String TOOL_RUN_METHOD = "run_test_method";
	public static final String TOOL_REFRESH = "refresh_projects";

	public record RefreshResult(boolean ok, String markdown) {
	}

	private BmMcpTools() {
	}

	public static List<Map<String, Object>> descriptors() {
		return List.of(
				toolDescriptor(TOOL_REFRESH,
						"Refresh one or more Eclipse projects from the filesystem (equivalent of right-click"
								+ " > Refresh), then trigger an incremental workspace build and wait for it"
								+ " to finish. Run this after editing files on disk and before any run_* tool"
								+ " so tests execute against fresh compiled code. Reports compile errors if any.",
						Map.of("projects", paramStringArray("Eclipse project names to refresh.")),
						List.of("projects")),
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

	private static Map<String, Object> paramStringArray(String desc) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", "string");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "array");
		m.put("items", item);
		m.put("minItems", 1);
		m.put("description", desc);
		return m;
	}

	public static RefreshResult refreshProjects(List<String> names) {
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		List<String> refreshed = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		List<IProject> resolved = new ArrayList<>();

		for (String name : names) {
			IProject p = ws.getRoot().getProject(name);
			if (!p.exists()) {
				errors.add(name + ": project not found in workspace");
				continue;
			}
			if (!p.isOpen()) {
				errors.add(name + ": project is closed");
				continue;
			}
			try {
				p.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
				refreshed.add(name);
				resolved.add(p);
			} catch (CoreException e) {
				errors.add(name + ": refresh failed — " + e.getMessage());
			}
		}

		if (!resolved.isEmpty()) {
			try {
				ws.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, new NullProgressMonitor());
			} catch (CoreException e) {
				errors.add("workspace build: " + e.getMessage());
			}
			waitForBuildJobs();
		}

		List<String> compileErrors = new ArrayList<>();
		for (IProject p : resolved) {
			try {
				IMarker[] markers = p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
				for (IMarker m : markers) {
					int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
					if (sev != IMarker.SEVERITY_ERROR) {
						continue;
					}
					String path = m.getResource() == null ? p.getName()
							: m.getResource().getFullPath().toString();
					int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
					String message = m.getAttribute(IMarker.MESSAGE, "(no message)");
					compileErrors.add(path + (line > 0 ? ":" + line : "") + " — " + message);
				}
			} catch (CoreException e) {
				errors.add(p.getName() + ": could not read problem markers — " + e.getMessage());
			}
		}

		boolean ok = errors.isEmpty() && compileErrors.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Refresh — ").append(ok ? "OK" : "ISSUES").append("\n\n");
		sb.append("Refreshed: ").append(refreshed.size()).append("/").append(names.size()).append("\n");
		if (!refreshed.isEmpty()) {
			sb.append(" - ").append(String.join(", ", refreshed)).append("\n");
		}
		if (!errors.isEmpty()) {
			sb.append("\n## Errors\n");
			for (String e : errors) {
				sb.append(" - ").append(e).append("\n");
			}
		}
		if (!compileErrors.isEmpty()) {
			int cap = 50;
			sb.append("\n## Compile errors (").append(compileErrors.size()).append(")\n");
			int shown = 0;
			for (String ce : compileErrors) {
				if (shown++ >= cap) {
					sb.append(" - ... ").append(compileErrors.size() - cap).append(" more\n");
					break;
				}
				sb.append(" - ").append(ce).append("\n");
			}
			sb.append("\nFix these before running tests — the launch may use stale class files.\n");
		}
		return new RefreshResult(ok, sb.toString());
	}

	public static List<String> asStringList(Object value) {
		if (value == null) {
			return List.of();
		}
		if (value instanceof List<?> list) {
			List<String> out = new ArrayList<>(list.size());
			for (Object o : list) {
				if (o != null) {
					out.add(o.toString());
				}
			}
			return out;
		}
		if (value instanceof String s) {
			return List.of(s);
		}
		return List.of(value.toString());
	}

	private static void waitForBuildJobs() {
		try {
			Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, new NullProgressMonitor());
			Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (OperationCanceledException ignored) {
			// best-effort wait
		}
	}
}
