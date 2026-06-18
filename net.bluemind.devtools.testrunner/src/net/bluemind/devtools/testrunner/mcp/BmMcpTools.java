package net.bluemind.devtools.testrunner.mcp;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import net.bluemind.devtools.testrunner.Activator;
import net.bluemind.devtools.testrunner.PomPropertyReader;

public final class BmMcpTools {

	public static final String TOOL_RUN_BUNDLE = "run_bundle_tests";
	public static final String TOOL_RUN_CLASS = "run_class_tests";
	public static final String TOOL_RUN_METHOD = "run_test_method";
	public static final String TOOL_REFRESH = "refresh_projects";
	public static final String TOOL_GET_PROBLEMS = "get_problems";
	public static final String TOOL_CLEAN = "clean_projects";
	public static final String TOOL_RELOAD_TARGET = "reload_target_platform";
	public static final String TOOL_IMPORT_PROJECTS = "import_projects";
	public static final String TOOL_OPEN_PROJECTS = "open_projects";

	public record RefreshResult(boolean ok, String markdown) {
	}

	/** Result of a tool that returns a markdown report instead of a test run. */
	public record ToolResult(boolean ok, String markdown) {
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
						List.of("project", "className", "methodName")),
				toolDescriptor(TOOL_GET_PROBLEMS,
						"List JDT/PDE problem markers (compile errors and warnings) without building."
								+ " Omit 'projects' to scan every open project in the workspace.",
						Map.of(
								"projects", paramStringArrayOpt(
										"Eclipse project names to inspect. Empty/omitted = all open projects."),
								"severity", paramSeverity()),
						List.of()),
				toolDescriptor(TOOL_CLEAN,
						"Project > Clean: discard build state and rebuild. Omit 'projects' to clean every"
								+ " open project. Reports compile errors afterwards.",
						Map.of(
								"projects", paramStringArrayOpt(
										"Eclipse project names to clean. Empty/omitted = all open projects."),
								"build", paramBoolean("Rebuild after cleaning (default true).")),
						List.of()),
				toolDescriptor(TOOL_RELOAD_TARGET,
						"Reload and re-resolve the active workspace target platform (equivalent of the"
								+ " 'Reload Target Platform' button). Use after the target definition or its"
								+ " p2 repository changed. Takes no arguments.",
						Map.of(),
						List.of()),
				toolDescriptor(TOOL_IMPORT_PROJECTS,
						"Import existing Eclipse projects found on disk (File > Import > Existing Projects,"
								+ " searching nested directories). Projects already in the workspace are skipped.",
						Map.of("path", paramString(
								"Root directory to scan for .project files. Omitted = BlueMind repo root"
										+ " derived from the global POM.")),
						List.of()),
				toolDescriptor(TOOL_OPEN_PROJECTS,
						"Open one or more closed Eclipse projects (equivalent of right-click > Open Project),"
								+ " then trigger an incremental workspace build. Reports compile errors afterwards.",
						Map.of("projects", paramStringArray("Eclipse project names to open.")),
						List.of("projects")));
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

	private static Map<String, Object> paramStringArrayOpt(String desc) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", "string");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "array");
		m.put("items", item);
		m.put("description", desc);
		return m;
	}

	private static Map<String, Object> paramBoolean(String desc) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "boolean");
		m.put("description", desc);
		return m;
	}

	private static Map<String, Object> paramSeverity() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "string");
		m.put("description", "Lowest severity to report: 'error' (default), 'warning' or 'all'.");
		m.put("enum", List.of("error", "warning", "all"));
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

	public static boolean isTextTool(String name) {
		return TOOL_GET_PROBLEMS.equals(name) || TOOL_CLEAN.equals(name)
				|| TOOL_RELOAD_TARGET.equals(name) || TOOL_IMPORT_PROJECTS.equals(name)
				|| TOOL_OPEN_PROJECTS.equals(name);
	}

	public static ToolResult invokeText(String tool, Map<String, Object> args) {
		switch (tool) {
		case TOOL_GET_PROBLEMS:
			return getProblems(asStringList(args.get("projects")), str(args, "severity"));
		case TOOL_CLEAN:
			return cleanProjects(asStringList(args.get("projects")), boolArg(args, "build", true));
		case TOOL_RELOAD_TARGET:
			return reloadTargetPlatform();
		case TOOL_IMPORT_PROJECTS:
			return importProjects(str(args, "path"));
		case TOOL_OPEN_PROJECTS:
			return openProjects(asStringList(args.get("projects")));
		default:
			throw new IllegalArgumentException("Unknown tool: " + tool);
		}
	}

	public static ToolResult getProblems(List<String> names, String severity) {
		List<String> errors = new ArrayList<>();
		List<IProject> targets = resolveProjects(names, errors);
		int minSeverity = minSeverity(severity);

		List<String> lines = new ArrayList<>();
		int errorCount = 0;
		int warningCount = 0;
		for (IProject p : targets) {
			try {
				IMarker[] markers = p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
				for (IMarker m : markers) {
					int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
					if (sev == IMarker.SEVERITY_ERROR) {
						errorCount++;
					} else if (sev == IMarker.SEVERITY_WARNING) {
						warningCount++;
					}
					if (sev >= minSeverity) {
						lines.add(formatMarker(p, m, sev));
					}
				}
			} catch (CoreException e) {
				errors.add(p.getName() + ": could not read markers — " + e.getMessage());
			}
		}

		boolean ok = errorCount == 0 && errors.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Problems — ").append(ok ? "no errors" : "errors present").append("\n\n");
		sb.append("Scanned ").append(targets.size()).append(" project(s) | Errors: ").append(errorCount)
				.append(" | Warnings: ").append(warningCount).append("\n");
		appendList(sb, "Errors resolving projects", errors);
		appendCapped(sb, "Reported markers", lines, 100);
		return new ToolResult(ok, sb.toString());
	}

	public static ToolResult cleanProjects(List<String> names, boolean rebuild) {
		List<String> errors = new ArrayList<>();
		List<IProject> targets = resolveProjects(names, errors);
		List<String> cleaned = new ArrayList<>();
		for (IProject p : targets) {
			try {
				p.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());
				if (rebuild) {
					p.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
				}
				cleaned.add(p.getName());
			} catch (CoreException e) {
				errors.add(p.getName() + ": build failed — " + e.getMessage());
			}
		}
		waitForBuildJobs();

		List<String> compileErrors = new ArrayList<>();
		for (IProject p : targets) {
			try {
				IMarker[] markers = p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
				for (IMarker m : markers) {
					int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
					if (sev == IMarker.SEVERITY_ERROR) {
						compileErrors.add(formatMarker(p, m, sev));
					}
				}
			} catch (CoreException e) {
				errors.add(p.getName() + ": could not read markers — " + e.getMessage());
			}
		}

		boolean ok = errors.isEmpty() && compileErrors.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Clean — ").append(ok ? "OK" : "ISSUES").append("\n\n");
		sb.append(rebuild ? "Cleaned + rebuilt: " : "Cleaned: ").append(cleaned.size()).append(" project(s)\n");
		appendList(sb, "Errors", errors);
		appendCapped(sb, "Compile errors", compileErrors, 50);
		return new ToolResult(ok, sb.toString());
	}

	public static ToolResult reloadTargetPlatform() {
		BundleContext ctx = Activator.getDefault().getBundle().getBundleContext();
		ServiceReference<ITargetPlatformService> ref = ctx.getServiceReference(ITargetPlatformService.class);
		if (ref == null) {
			return new ToolResult(false, "ITargetPlatformService not available.");
		}
		ITargetPlatformService service = ctx.getService(ref);
		try {
			if (service.getWorkspaceTargetHandle() == null) {
				return new ToolResult(false, "No workspace target platform is set — nothing to reload.");
			}
			ITargetDefinition target = service.getWorkspaceTargetHandle().getTargetDefinition();
			LoadTargetDefinitionJob job = new LoadTargetDefinitionJob(target);
			job.schedule();
			job.join();
			IStatus result = job.getResult();
			boolean ok = result == null || result.isOK();
			String name = target.getName() == null ? "(unnamed)" : target.getName();
			StringBuilder sb = new StringBuilder();
			sb.append("# Reload target platform — ").append(ok ? "OK" : "FAILED").append("\n\n");
			sb.append("Target: ").append(name).append("\n");
			if (!ok) {
				sb.append("Status: ").append(result.getMessage()).append("\n");
			}
			return new ToolResult(ok, sb.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new ToolResult(false, "Interrupted while reloading target platform.");
		} catch (CoreException e) {
			return new ToolResult(false, "Reload failed: " + e.getMessage());
		} finally {
			ctx.ungetService(ref);
		}
	}

	public static ToolResult openProjects(List<String> names) {
		if (names == null || names.isEmpty()) {
			return new ToolResult(false, "Missing required argument: projects");
		}
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		List<String> opened = new ArrayList<>();
		List<String> alreadyOpen = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		for (String name : names) {
			IProject p = ws.getRoot().getProject(name);
			if (!p.exists()) {
				errors.add(name + ": project not found in workspace");
				continue;
			}
			if (p.isOpen()) {
				alreadyOpen.add(name);
				continue;
			}
			try {
				p.open(new NullProgressMonitor());
				opened.add(name);
			} catch (CoreException e) {
				errors.add(name + ": open failed — " + e.getMessage());
			}
		}

		if (!opened.isEmpty()) {
			try {
				ws.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, new NullProgressMonitor());
			} catch (CoreException e) {
				errors.add("workspace build: " + e.getMessage());
			}
			waitForBuildJobs();
		}

		List<String> compileErrors = new ArrayList<>();
		for (String name : opened) {
			IProject p = ws.getRoot().getProject(name);
			try {
				IMarker[] markers = p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
				for (IMarker m : markers) {
					int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
					if (sev == IMarker.SEVERITY_ERROR) {
						compileErrors.add(formatMarker(p, m, sev));
					}
				}
			} catch (CoreException e) {
				errors.add(name + ": could not read markers — " + e.getMessage());
			}
		}

		boolean ok = errors.isEmpty() && compileErrors.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Open projects — ").append(ok ? "OK" : "ISSUES").append("\n\n");
		sb.append("Opened: ").append(opened.size()).append(" | Already open: ").append(alreadyOpen.size())
				.append("\n");
		appendList(sb, "Opened", opened);
		appendList(sb, "Already open", alreadyOpen);
		appendList(sb, "Errors", errors);
		appendCapped(sb, "Compile errors", compileErrors, 50);
		return new ToolResult(ok, sb.toString());
	}

	public static ToolResult importProjects(String rootPath) {
		Path root;
		if (rootPath != null && !rootPath.isBlank()) {
			root = Path.of(rootPath);
		} else {
			Optional<Path> globalPom = PomPropertyReader.findGlobalPom();
			if (globalPom.isEmpty()) {
				return new ToolResult(false,
						"No 'path' given and the BlueMind global POM was not found to derive the repo root.");
			}
			// <root>/open/global/pom.xml -> walk up to <root>
			root = globalPom.get().getParent().getParent().getParent();
		}
		if (root == null || !Files.isDirectory(root)) {
			return new ToolResult(false, "Root path is not a directory: " + root);
		}

		IWorkspace ws = ResourcesPlugin.getWorkspace();
		List<String> imported = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		Set<String> skipDirs = Set.of(".git", "node_modules", "target", "bin", ".metadata");

		try {
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
					if (skipDirs.contains(dirName)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					Path dotProject = dir.resolve(".project");
					if (Files.isRegularFile(dotProject)) {
						importOne(ws, dotProject, imported, skipped, errors);
						// One .project per Eclipse project: do not descend further.
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			errors.add("scan failed: " + e.getMessage());
		}

		boolean ok = errors.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Import projects — ").append(ok ? "OK" : "ISSUES").append("\n\n");
		sb.append("Root: ").append(root).append("\n");
		sb.append("Imported: ").append(imported.size()).append(" | Already present: ").append(skipped.size())
				.append("\n");
		appendCapped(sb, "Imported", imported, 100);
		appendList(sb, "Errors", errors);
		return new ToolResult(ok, sb.toString());
	}

	private static void importOne(IWorkspace ws, Path dotProject, List<String> imported, List<String> skipped,
			List<String> errors) {
		try {
			IPath descPath = IPath.fromOSString(dotProject.toString());
			IProjectDescription desc = ws.loadProjectDescription(descPath);
			IProject project = ws.getRoot().getProject(desc.getName());
			if (project.exists()) {
				skipped.add(desc.getName());
				return;
			}
			project.create(desc, new NullProgressMonitor());
			project.open(new NullProgressMonitor());
			imported.add(desc.getName());
		} catch (CoreException e) {
			errors.add(dotProject + ": " + e.getMessage());
		}
	}

	private static List<IProject> resolveProjects(List<String> names, List<String> errors) {
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		List<IProject> targets = new ArrayList<>();
		if (names == null || names.isEmpty()) {
			for (IProject p : ws.getRoot().getProjects()) {
				if (p.isOpen()) {
					targets.add(p);
				}
			}
			return targets;
		}
		for (String name : names) {
			IProject p = ws.getRoot().getProject(name);
			if (!p.exists()) {
				errors.add(name + ": project not found in workspace");
			} else if (!p.isOpen()) {
				errors.add(name + ": project is closed");
			} else {
				targets.add(p);
			}
		}
		return targets;
	}

	private static String formatMarker(IProject p, IMarker m, int severity) {
		String path = m.getResource() == null ? p.getName() : m.getResource().getFullPath().toString();
		int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
		String message = m.getAttribute(IMarker.MESSAGE, "(no message)");
		String tag = severity == IMarker.SEVERITY_ERROR ? "[ERROR]"
				: severity == IMarker.SEVERITY_WARNING ? "[WARN]" : "[INFO]";
		return tag + " " + path + (line > 0 ? ":" + line : "") + " — " + message;
	}

	private static int minSeverity(String severity) {
		if (severity == null) {
			return IMarker.SEVERITY_ERROR;
		}
		switch (severity.toLowerCase()) {
		case "all":
			return IMarker.SEVERITY_INFO;
		case "warning":
			return IMarker.SEVERITY_WARNING;
		default:
			return IMarker.SEVERITY_ERROR;
		}
	}

	private static boolean boolArg(Map<String, Object> args, String key, boolean def) {
		Object v = args == null ? null : args.get(key);
		if (v == null) {
			return def;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		return Boolean.parseBoolean(v.toString());
	}

	private static void appendList(StringBuilder sb, String title, List<String> items) {
		if (items.isEmpty()) {
			return;
		}
		sb.append("\n## ").append(title).append(" (").append(items.size()).append(")\n");
		for (String item : items) {
			sb.append(" - ").append(item).append("\n");
		}
	}

	private static void appendCapped(StringBuilder sb, String title, List<String> items, int cap) {
		if (items.isEmpty()) {
			return;
		}
		sb.append("\n## ").append(title).append(" (").append(items.size()).append(")\n");
		int shown = 0;
		for (String item : items) {
			if (shown++ >= cap) {
				sb.append(" - ... ").append(items.size() - cap).append(" more\n");
				break;
			}
			sb.append(" - ").append(item).append("\n");
		}
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
