package net.bluemind.devtools.testrunner.mcp;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.BundleSpecification;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.osgi.service.resolver.ImportPackageSpecification;
import org.eclipse.osgi.service.resolver.ResolverError;
import org.eclipse.osgi.service.resolver.State;
import org.eclipse.osgi.service.resolver.VersionConstraint;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.IWorkingSetManager;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import net.bluemind.devtools.Activator;
import net.bluemind.devtools.testrunner.PomPropertyReader;

public final class BmMcpTools {

	public static final String TOOL_RUN_BUNDLE = "run_bundle_tests";
	public static final String TOOL_RUN_CLASS = "run_class_tests";
	public static final String TOOL_RUN_METHOD = "run_test_method";
	public static final String TOOL_REFRESH = "refresh_projects";
	public static final String TOOL_GET_PROBLEMS = "get_problems";
	public static final String TOOL_GET_BUILD_STATUS = "get_build_status";
	public static final String TOOL_CLEAN = "clean_projects";
	public static final String TOOL_RELOAD_TARGET = "reload_target_platform";
	public static final String TOOL_IMPORT_PROJECTS = "import_projects";
	public static final String TOOL_OPEN_PROJECTS = "open_projects";
	public static final String TOOL_CLOSE_PROJECTS = "close_projects";
	public static final String TOOL_REMOVE_PROJECTS = "remove_projects";
	public static final String TOOL_SYNC_PROJECTS = "sync_projects";
	public static final String TOOL_LIST_PROJECTS = "list_projects";
	public static final String TOOL_WORKSPACE_INFO = "workspace_info";
	public static final String TOOL_SYNC_WORKING_SETS = "sync_working_sets";
	public static final String TOOL_APPLY_BATCH = "apply_workspace_batch";
	public static final String TOOL_BUNDLE_STATE = "get_bundle_state";
	public static final String TOOL_DOCTOR_SNAPSHOT = "doctor_snapshot";
	public static final String TOOL_DOCTOR_STATUS = "doctor_status";
	public static final String TOOL_LOCATE_TYPE = "locate_type";

	/**
	 * Id of the working set PAGE JDT registers on {@code org.eclipse.ui.workingSets} —
	 * the value of JDT's own {@code IWorkingSetIDs.JAVA}, hardcoded because that
	 * interface is internal. {@link IWorkingSet#setId(String)} is looked up in the
	 * working set registry, and an id that matches no extension is not rejected: the set
	 * is created and persisted, simply without page, icon, element adapter and
	 * {@code JavaWorkingSetUpdater}. It then misses every Java-typed selector (Package
	 * Explorer) while still showing in the generic ones (Project Explorer), and stops
	 * following renames and deletions.
	 */
	private static final String JAVA_WORKING_SET_ID = "org.eclipse.jdt.ui.JavaWorkingSetPage";

	/**
	 * JDT problem ids the doctor classifies on, mapped to a stable, language-independent
	 * name. The ids come from {@link IProblem} constants and never from literals: the
	 * whole point is to stop keying remedies on the wording of a message, which changes
	 * with the JDT version and with the IDE language.
	 */
	private static final Map<Integer, String> PROBLEM_KINDS = Map.ofEntries(
			Map.entry(IProblem.UndefinedType, "undefined-type"),
			Map.entry(IProblem.ImportNotFound, "import-not-found"),
			Map.entry(IProblem.UndefinedName, "undefined-name"),
			Map.entry(IProblem.AbstractMethodMustBeImplemented, "abstract-method-not-implemented"),
			Map.entry(IProblem.AbstractMethodMustBeImplementedOverConcreteMethod,
					"abstract-method-not-implemented"),
			Map.entry(IProblem.EnumAbstractMethodMustBeImplemented, "abstract-method-not-implemented"),
			Map.entry(IProblem.IsClassPathCorrect, "classpath-incorrect"),
			Map.entry(IProblem.IsClassPathCorrectWithReferencingType, "classpath-incorrect"),
			Map.entry(IProblem.HierarchyHasProblems, "hierarchy-has-problems"),
			// A member's SIGNATURE cites a type the compiler cannot see, so the reference
			// the compiler complains about is not in the source being compiled. Falling out
			// as "other" left the doctor with no type-level fact at all on release/5.6:
			// three projects handed back on `refers to the missing type SecurityContext`,
			// fixed by the very clean the classification already prescribes.
			Map.entry(IProblem.MissingTypeInMethod, "missing-type-in-signature"),
			Map.entry(IProblem.MissingTypeInConstructor, "missing-type-in-signature"),
			Map.entry(IProblem.MissingTypeInLambda, "missing-type-in-signature"),
			Map.entry(IProblem.MissingTypeForInference, "missing-type-in-signature"));

	/**
	 * Problem kinds whose first argument IS the name that could not be resolved. Not
	 * every kind qualifies: {@code hierarchy-has-problems} names the type that has the
	 * broken hierarchy, not the missing one, so it deliberately carries no name.
	 */
	private static final Set<String> NAMING_KINDS = Set.of("undefined-type", "import-not-found", "undefined-name",
			"classpath-incorrect");

	/**
	 * Problem kinds whose LAST argument is that name. JDT's own templates decide it:
	 * {@code The method {1}({2}) from the type {0} refers to the missing type {3}},
	 * {@code The constructor {0}({1}) refers to the missing type {2}}. Argument 0 there
	 * is the DECLARING type, which resolves perfectly well — reading it would send the
	 * doctor cleaning a project that has nothing wrong with it.
	 */
	private static final Set<String> TRAILING_NAME_KINDS = Set.of("missing-type-in-signature");

	/** Marker kinds that make a type name worth locating (see {@link TypeLocator}). */
	private static final Set<String> UNRESOLVED_REFERENCE_KINDS = Set.of("undefined-type", "import-not-found",
			"classpath-incorrect", "missing-type-in-signature");

	/**
	 * A workspace really out of shape can ask for thousands of distinct names; the
	 * section stays bounded and says so rather than growing without limit.
	 */
	private static final int UNRESOLVED_TYPES_CAP = 200;

	/** A generated source folder is recognised by name — the only IDE-language-free way. */
	private static final String GENERATED_HINT = "generated";

	/**
	 * PDE metadata a plugin project cannot be understood without. Present on disk but
	 * absent from Eclipse's resource tree = Eclipse never refreshed the project, and
	 * every dependent fails to resolve while the project itself looks green.
	 */
	private static final List<String> PDE_METADATA_FILES = List.of("META-INF/MANIFEST.MF", "build.properties",
			".classpath", "plugin.xml");

	// Working-set grouping layouts (see sync_working_sets 'layout' argument).
	public static final String WS_LAYOUT_FLAT2 = "flat2";
	public static final String WS_LAYOUT_FLAT3 = "flat3";
	public static final String WS_LAYOUT_HYBRID = "hybrid";
	private static final String WS_DEFAULT_LAYOUT = WS_LAYOUT_HYBRID;
	// hybrid: this branch alone is split one level deeper; its sub-buckets below
	// WS_MISC_FLOOR projects are folded into a single "<branch>/~misc" set.
	private static final String WS_HYBRID_DEEP_BRANCH = "open/parent";
	private static final int WS_MISC_FLOOR = 5;

	private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", "bin", ".metadata");

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
						"List JDT/PDE problem markers (compile errors and warnings). Read-only: does not"
								+ " trigger a build. Omit 'projects' to scan every open project. Set"
								+ " 'waitForBuild' to block until the workspace stops refreshing and building,"
								+ " so markers aren't read mid-build. Every marker is also returned in a"
								+ " trailing JSON block (the markdown list is capped for readability), with"
								+ " 'problemId' (the IProblem id), 'problemKind' (undefined-type,"
								+ " import-not-found, undefined-name, abstract-method-not-implemented,"
								+ " classpath-incorrect, hierarchy-has-problems,"
								+ " missing-type-in-signature, other) and 'unresolvedName'"
								+ " (the missing name, untranslated) — so a caller never has to parse a message.",
						Map.of(
								"projects", paramStringArrayOpt(
										"Eclipse project names to inspect. Empty/omitted = all open projects."),
								"severity", paramSeverity(),
								"waitForBuild", paramBoolean(
										"Wait for any running build to finish before reading markers (default false).")),
						List.of()),
				toolDescriptor(TOOL_GET_BUILD_STATUS,
						"Report whether Eclipse is currently building (auto-build and manual/incremental"
								+ " build jobs). Read-only; use as a sync point before get_problems to avoid"
								+ " reading stale markers mid-build. Takes no arguments.",
						Map.of(),
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
						List.of("projects")),
				toolDescriptor(TOOL_CLOSE_PROJECTS,
						"Close one or more open Eclipse projects (equivalent of right-click > Close Project) —"
								+ " frees JDT/PDE from indexing and building them. Nothing on disk is touched and"
								+ " a closed project can always be reopened with open_projects. Triggers an"
								+ " incremental workspace build afterwards so dependents pick up the now-closed"
								+ " provider.",
						Map.of("projects", paramStringArray("Eclipse project names to close.")),
						List.of("projects")),
				toolDescriptor(TOOL_REMOVE_PROJECTS,
						"Remove project(s) from the workspace AND delete their on-disk residue (.project,"
								+ ".classpath, .settings/, bin/, target/) — for an untracked shell git would not"
								+ " recognise from any commit. All-or-nothing: refuses the WHOLE list if any"
								+ " project has a tracked file (or git status could not be resolved) or any"
								+ " content beyond the expected residue — that might be a bundle created and not"
								+ " yet 'git add'-ed. Consent required (workspace.consent.projects), same as"
								+ " opening/importing.",
						Map.of("projects", paramStringArray("Eclipse project names to remove.")),
						List.of("projects")),
				toolDescriptor(TOOL_SYNC_PROJECTS,
						"Full disk <-> workspace project diff: projects to import, obsolete workspace projects"
								+ " (removed on disk, never deletes content), and moved projects. Dry-run by"
								+ " default; apply requires user consent (workspace.consent.projects preference).",
						Map.of(
								"path", paramString(
										"Root directory to scan. Omitted = BlueMind repo root from the global POM."),
								"apply", paramBoolean("Perform the changes (default false = dry-run report only)."),
								"removeObsolete", paramBoolean(
										"Remove obsolete workspace projects when apply=true (default true).")),
						List.of()),
				toolDescriptor(TOOL_LIST_PROJECTS,
						"List workspace projects: path relative to the repo root, open/closed, working set"
								+ " membership, marker counts, missing-location flag. Read-only.",
						Map.of("scope", paramScope()),
						List.of()),
				toolDescriptor(TOOL_WORKSPACE_INFO,
						"Repo root, current git branch and workspace path/projects for this Eclipse instance."
								+ " Read-only; use to target the right repo when multiple instances are running.",
						Map.of(), List.of()),
				toolDescriptor(TOOL_SYNC_WORKING_SETS,
						"Sync working sets to reflect the repo directory layout. 'layout' picks the"
								+ " grouping: 'flat2' = 2 path levels (few sets, one is huge); 'flat3' = 3"
								+ " levels; 'hybrid' (default) = 2 levels except open/parent split to 3, its"
								+ " sub-groups under 5 projects folded into open/parent/~misc. Only working sets"
								+ " created by this tool are ever touched — a hand-made set with the same name is"
								+ " reported and skipped. Dry-run by default; apply requires the working sets"
								+ " consent. reset=true wipes every working set in the workspace (including"
								+ " hand-made ones, never the projects themselves) and recreates the full layout"
								+ " — dry-run lists every set that would be deleted.",
						Map.of(
								"path", paramString(
										"Root directory the set names are relative to. Omitted = BlueMind repo root."),
								"apply", paramBoolean("Perform the changes (default false = dry-run report only)."),
								"reset", paramBoolean(
										"Delete ALL working sets and recreate them from scratch (default false)."),
								"layout", paramString(
										"Grouping layout: flat2 | flat3 | hybrid (default hybrid).")),
						List.of()),
				toolDescriptor(TOOL_APPLY_BATCH,
						"Atomic workspace batch for the Eclipse doctor: suspend auto-build, then in ONE"
								+ " workspace operation open the given closed projects, import the given ones"
								+ " from disk, refresh the given ones from the filesystem and close the given"
								+ " open ones (closes run last, after open/import/refresh); then clean the"
								+ " given ones and run a SINGLE build, and restore auto-build. Avoids one build"
								+ " per remedy. Auto-build is always restored (even on error, and via a net at"
								+ " plugin start/stop). Consent (workspace.consent.projects) is required ONLY"
								+ " when 'open' or 'import' is non-empty — 'close' never asks for consent (same"
								+ " policy as close_projects: local IDE state, nothing on disk, trivially"
								+ " reversible), and a refresh/clean/close-only batch changes no membership"
								+ " Eclipse would need to persist and asks nothing. Reports compile errors"
								+ " after.",
						Map.of(
								"open", paramStringArrayOpt("Closed workspace project names to open."),
								"import", paramStringArrayOpt(
										"Project names to import from disk (their .project is located under 'path')."),
								"refresh", paramStringArrayOpt(
										"Open workspace project names to refresh from the filesystem."),
								"clean", paramStringArrayOpt(
										"Open workspace project names to clean (discard build state)."),
								"close", paramStringArrayOpt(
										"Open workspace project names to close. Runs after open/import/refresh."),
								"path", paramString(
										"Root scanned to resolve 'import' names. Omitted = BlueMind repo root."),
								"deleteGenerated", paramStringArrayOpt(
										"Workspace-relative paths of generated source files to delete. ALL OR"
												+ " NOTHING: each path must sit under a generated kind=\"src\""
												+ " folder of its project, be untracked by git and exist under the"
												+ " repo root; one failure cancels the whole list and nothing is"
												+ " deleted. Deleted through the resource tree, in the first phase"
												+ " of the batch."),
								"build", paramBoolean("Run the single final build (default true).")),
						List.of()),
				toolDescriptor(TOOL_BUNDLE_STATE,
						"PDE's own view of bundles — the ground truth behind unresolved dependencies."
								+ " Per bundle: workspace or target platform, resolved yes/no, PDE resolver"
								+ " errors (MISSING_REQUIRE_BUNDLE, MISSING_IMPORT_PACKAGE, SINGLETON_SELECTION,"
								+ " …), Require-Bundle requirements with their version ranges and reexport flag,"
								+ " and 'metadataInvisible': the PDE metadata files present on disk yet absent"
								+ " from Eclipse's resource tree (a project Eclipse never refreshed — invisible"
								+ " to every dependent while looking green itself). Read-only. Omit 'bundles' for"
								+ " the workspace models without their exports; name bundles to look them up"
								+ " wherever they live (workspace or target) with exports and imports included.",
						Map.of("bundles", paramStringArrayOpt(
								"Bundle symbolic names. Empty/omitted = every workspace plugin model.")),
						List.of()),
				toolDescriptor(TOOL_DOCTOR_SNAPSHOT,
						"One read-only aggregate for the Eclipse doctor: workspace_info + get_problems +"
								+ " list_projects + get_bundle_state in a single JSON block. The MCP server"
								+ " serialises calls, so four reads per diagnostic pass add up in wall clock;"
								+ " this collapses them into one round-trip. Adds 'unresolvedTypes' (every"
								+ " distinct name an error marker cannot resolve, located as jdt-visible /"
								+ " disk-only / closed-provider / nowhere — see locate_type) and"
								+ " 'settled'/'settleRounds', which"
								+ " say whether the workspace was actually quiet when the markers were read."
								+ " Pure facts, no verdict.",
						Map.of(
								"severity", paramSeverity(),
								"waitForBuild", paramBoolean(
										"Wait for refresh and build jobs to finish before reading markers"
												+ " (default true)."),
								"bundles", paramStringArrayOpt(
										"Extra bundle symbolic names to look up beyond the workspace models"
												+ " (target-platform lookups, with exports).")),
						List.of()),
				toolDescriptor(TOOL_LOCATE_TYPE,
						"Where does a type actually live? Per name: 'jdt-visible' (the JDT model knows it —"
								+ " the resource tree is fine, only the build state is stale), 'disk-only'"
								+ " (<Name>.java is in a source folder of an open project but JDT does not see"
								+ " it — the resource tree is out of sync with the disk), 'closed-provider'"
								+ " (<Name>.java is in a source folder of a CLOSED project — the type exists,"
								+ " it is simply not being looked at; open the supplier) or 'nowhere' (neither"
								+ " JDT, nor the disk of an open project, nor the disk of a closed one — a real"
								+ " code error, or a codegen output whose input was deleted). Also returns the"
								+ " workspace projects JDT found it in, the projects it exists in on disk, the"
								+ " disk path relative to the repo root, and whether git tracks that file."
								+ " Read-only; waits for the JDT indexer before answering.",
						Map.of("names", paramStringArray("Type names, simple or fully qualified.")),
						List.of("names")),
				toolDescriptor(TOOL_DOCTOR_STATUS,
						"Show or hide a purely informative Eclipse progress entry while the doctor runs an"
								+ " EXTERNAL Maven rebuild. The job holds no scheduling rule, so it never blocks"
								+ " the workspace, and it is not cancellable in any meaningful sense (cancelling"
								+ " it would not kill the Maven process). It auto-closes after 20 minutes so a"
								+ " script killed between 'start' and 'end' cannot leak it.",
						Map.of(
								"phase", paramString("'start' to show the entry, 'end' to close it."),
								"detail", paramString("Optional detail appended to the job label.")),
						List.of("phase")));
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

	private static Map<String, Object> paramScope() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "string");
		m.put("description", "Which projects to list: 'all' (default), 'errors' or 'closed'.");
		m.put("enum", List.of("all", "errors", "closed"));
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
		return TOOL_GET_PROBLEMS.equals(name) || TOOL_GET_BUILD_STATUS.equals(name)
				|| TOOL_CLEAN.equals(name)
				|| TOOL_RELOAD_TARGET.equals(name) || TOOL_IMPORT_PROJECTS.equals(name)
				|| TOOL_OPEN_PROJECTS.equals(name) || TOOL_CLOSE_PROJECTS.equals(name)
				|| TOOL_REMOVE_PROJECTS.equals(name)
				|| TOOL_SYNC_PROJECTS.equals(name)
				|| TOOL_LIST_PROJECTS.equals(name) || TOOL_WORKSPACE_INFO.equals(name)
				|| TOOL_SYNC_WORKING_SETS.equals(name) || TOOL_APPLY_BATCH.equals(name)
				|| TOOL_BUNDLE_STATE.equals(name) || TOOL_DOCTOR_SNAPSHOT.equals(name)
				|| TOOL_DOCTOR_STATUS.equals(name) || TOOL_LOCATE_TYPE.equals(name);
	}

	public static ToolResult invokeText(String tool, Map<String, Object> args) {
		switch (tool) {
		case TOOL_GET_PROBLEMS:
			return getProblems(asStringList(args.get("projects")), str(args, "severity"),
					boolArg(args, "waitForBuild", false));
		case TOOL_GET_BUILD_STATUS:
			return buildStatus();
		case TOOL_CLEAN:
			return cleanProjects(asStringList(args.get("projects")), boolArg(args, "build", true));
		case TOOL_RELOAD_TARGET:
			return reloadTargetPlatform();
		case TOOL_IMPORT_PROJECTS:
			return importProjects(str(args, "path"));
		case TOOL_OPEN_PROJECTS:
			return openProjects(asStringList(args.get("projects")));
		case TOOL_CLOSE_PROJECTS:
			return closeProjects(asStringList(args.get("projects")));
		case TOOL_REMOVE_PROJECTS:
			return removeProjects(asStringList(args.get("projects")));
		case TOOL_SYNC_PROJECTS:
			return syncProjects(str(args, "path"), boolArg(args, "apply", false),
					boolArg(args, "removeObsolete", true));
		case TOOL_LIST_PROJECTS:
			return listProjects(str(args, "scope"));
		case TOOL_WORKSPACE_INFO:
			return workspaceInfo();
		case TOOL_SYNC_WORKING_SETS:
			return syncWorkingSets(str(args, "path"), boolArg(args, "apply", false),
					boolArg(args, "reset", false), str(args, "layout"));
		case TOOL_APPLY_BATCH:
			return applyWorkspaceBatch(asStringList(args.get("open")), asStringList(args.get("import")),
					asStringList(args.get("refresh")), asStringList(args.get("clean")),
					asStringList(args.get("close")), asStringList(args.get("deleteGenerated")), str(args, "path"),
					boolArg(args, "build", true));
		case TOOL_LOCATE_TYPE:
			return locateType(asStringList(args.get("names")));
		case TOOL_BUNDLE_STATE:
			return bundleState(asStringList(args.get("bundles")));
		case TOOL_DOCTOR_SNAPSHOT:
			return doctorSnapshot(str(args, "severity"), boolArg(args, "waitForBuild", true),
					asStringList(args.get("bundles")));
		case TOOL_DOCTOR_STATUS:
			return doctorStatus(str(args, "phase"), str(args, "detail"));
		default:
			throw new IllegalArgumentException("Unknown tool: " + tool);
		}
	}

	public static ToolResult getProblems(List<String> names, String severity, boolean waitForBuild) {
		ProblemsData data = problemsData(names, severity, waitForBuild);

		boolean ok = data.errorCount() == 0 && data.readErrors().isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Problems — ").append(ok ? "no errors" : "errors present").append("\n\n");
		sb.append("Scanned ").append(data.scanned()).append(" project(s) | Errors: ").append(data.errorCount())
				.append(" | Warnings: ").append(data.warningCount()).append("\n");
		appendList(sb, "Errors resolving projects", data.readErrors());
		appendCapped(sb, "Reported markers", data.lines(), 100);
		// Full, uncapped marker list for tooling (bm-eclipse-doctor) — the markdown
		// above is capped for readability, this block always carries every marker.
		appendJsonBlock(sb, data.json());
		return new ToolResult(ok, sb.toString());
	}

	/** Marker facts, shared by {@code get_problems} and {@code doctor_snapshot}. */
	private record ProblemsData(int scanned, int errorCount, int warningCount, List<String> lines,
			List<Map<String, Object>> markers, List<String> readErrors) {

		Map<String, Object> json() {
			return Map.of("errors", errorCount, "warnings", warningCount, "markers", markers);
		}
	}

	private static ProblemsData problemsData(List<String> names, String severity, boolean waitForBuild) {
		if (waitForBuild) {
			waitForBuildJobs();
		}
		List<String> readErrors = new ArrayList<>();
		List<IProject> targets = resolveProjects(names, readErrors);
		int minSeverity = minSeverity(severity);

		List<String> lines = new ArrayList<>();
		List<Map<String, Object>> jsonMarkers = new ArrayList<>();
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
						jsonMarkers.add(markerJson(p, m, sev));
					}
				}
			} catch (CoreException e) {
				readErrors.add(p.getName() + ": could not read markers — " + e.getMessage());
			}
		}
		return new ProblemsData(targets.size(), errorCount, warningCount, lines, jsonMarkers, readErrors);
	}

	public static ToolResult buildStatus() {
		int running = 0;
		int waiting = 0;
		List<String> jobs = new ArrayList<>();
		// Refresh families included on purpose: their absence is what made a previous
		// "idle" mean "the refresh has not scheduled the build yet".
		List<Job> all = new ArrayList<>();
		for (Object family : SETTLE_FAMILIES) {
			all.addAll(List.of(Job.getJobManager().find(family)));
		}
		for (Job j : all) {
			int state = j.getState();
			String label;
			if (state == Job.RUNNING) {
				running++;
				label = "running";
			} else if (state == Job.WAITING) {
				waiting++;
				label = "waiting";
			} else if (state == Job.SLEEPING) {
				label = "sleeping";
			} else {
				continue;
			}
			jobs.add(j.getName() + " [" + label + "]");
		}
		boolean building = running > 0 || waiting > 0;
		List<String> active = activeFamilies();

		StringBuilder sb = new StringBuilder();
		sb.append("# Build status — ").append(building ? "BUSY" : "settled").append("\n\n");
		sb.append("Running jobs: ").append(running).append(" | Waiting: ").append(waiting)
				.append(" | Active families: ").append(active.isEmpty() ? "none" : String.join(", ", active))
				.append("\n");
		appendList(sb, "Jobs", jobs);
		appendJsonBlock(sb, Map.of("building", building, "settled", active.isEmpty(), "running", running,
				"waiting", waiting, "activeFamilies", active, "jobs", jobs));
		return new ToolResult(true, sb.toString());
	}

	private static Map<String, Object> markerJson(IProject p, IMarker m, int severity) {
		String path = m.getResource() == null ? p.getName() : m.getResource().getFullPath().toString();
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("project", p.getName());
		json.put("path", path);
		json.put("line", m.getAttribute(IMarker.LINE_NUMBER, -1));
		json.put("severity", severity == IMarker.SEVERITY_ERROR ? "error"
				: severity == IMarker.SEVERITY_WARNING ? "warning" : "info");
		json.put("message", m.getAttribute(IMarker.MESSAGE, "(no message)"));
		// The two attributes that make a classifier possible without reading the message:
		// the IProblem id, and the problem's own arguments (so, the missing name itself,
		// untranslated). Two extra attribute reads on a marker already being iterated.
		int problemId = m.getAttribute(IJavaModelMarker.ID, -1);
		String kind = problemId < 0 ? null : PROBLEM_KINDS.getOrDefault(problemId, "other");
		if (kind == null && isBuildpathProblem(m)) {
			// Not an IProblem at all — JDT's "build path is incomplete" is its own marker
			// type, with no compiler problem id to look up. Perfectly identifiable by
			// type; it does not have to fall out as "unknown".
			kind = "build-path-incomplete";
		}
		json.put("problemId", problemId);
		json.put("problemKind", kind);
		json.put("unresolvedName", unresolvedName(kind, m.getAttribute(IJavaModelMarker.ARGUMENTS, null)));
		return json;
	}

	/**
	 * The missing name a marker of this kind carries, or null when its kind carries none.
	 * Which argument holds it is a property of the problem, not of the wording — see
	 * {@link #NAMING_KINDS} and {@link #TRAILING_NAME_KINDS}.
	 */
	private static String unresolvedName(String kind, String arguments) {
		if (kind == null) {
			return null;
		}
		if (NAMING_KINDS.contains(kind)) {
			return firstArgument(arguments);
		}
		if (TRAILING_NAME_KINDS.contains(kind)) {
			return lastArgument(arguments);
		}
		return null;
	}

	private static boolean isBuildpathProblem(IMarker m) {
		try {
			return m.isSubtypeOf(IJavaModelMarker.BUILDPATH_PROBLEM_MARKER);
		} catch (CoreException e) {
			return false;
		}
	}

	/** First problem argument of a JDT marker, or null — see {@link #problemArguments}. */
	static String firstArgument(String encoded) {
		String[] fields = problemArguments(encoded);
		return fields == null || fields[0].isBlank() ? null : fields[0];
	}

	/**
	 * Last problem argument of a JDT marker, or null — same decoder, same "ambiguous
	 * means I do not know" rule. The missing-type family names the type it cannot see
	 * at the END of the argument list.
	 */
	static String lastArgument(String encoded) {
		String[] fields = problemArguments(encoded);
		if (fields == null) {
			return null;
		}
		String last = fields[fields.length - 1];
		return last.isBlank() ? null : last;
	}

	/**
	 * Problem arguments of a JDT marker, or null when the encoding does not hold.
	 *
	 * <p>The format is {@code <count>:<arg>#<arg>…}, with an all-blank field standing for
	 * an empty argument. There is <em>no escaping</em>: an argument containing a
	 * {@code #} is written raw, which makes the string genuinely ambiguous — the count
	 * prefix is the only way to notice, and when the field count disagrees the honest
	 * answer is "I do not know". Verified by round-tripping the JDT encoder that
	 * produces the attribute; do not "fix" this into a backslash-unescaping decoder,
	 * that mangles a name containing a backslash.
	 *
	 * <p>Decoded here rather than imported: an {@code Import-Package} on
	 * {@code org.eclipse.jdt.internal.core.util} would tie the plugin to internals for
	 * one string split. Anything that does not parse yields null, and null is a real
	 * answer: the caller falls back to no name at all, never to the message text.
	 */
	private static String[] problemArguments(String encoded) {
		if (encoded == null || encoded.isBlank()) {
			return null;
		}
		int colon = encoded.indexOf(':');
		if (colon <= 0) {
			return null;
		}
		int count;
		try {
			count = Integer.parseInt(encoded.substring(0, colon));
		} catch (NumberFormatException e) {
			return null;
		}
		if (count <= 0) {
			return null;
		}
		String[] fields = encoded.substring(colon + 1).split("#", -1);
		return fields.length == count ? fields : null;
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

		try {
			runBatched("BlueMind Doctor — ouverture de " + names.size() + " projet(s)", monitor -> {
				SubMonitor sub = SubMonitor.convert(monitor, names.size());
				for (String name : names) {
					sub.subTask(name);
					sub.worked(1);
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
			}, buildMonitor -> {
				if (!opened.isEmpty()) {
					try {
						ws.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, buildMonitor);
					} catch (CoreException e) {
						errors.add("workspace build: " + e.getMessage());
					}
				}
			});
		} catch (CoreException e) {
			errors.add("batch open failed: " + e.getMessage());
		}
		waitForBuildJobs();

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

	/**
	 * Close project(s) — the mirror of {@link #openProjects(List)}. No consent is
	 * asked, matching open_projects: this is local IDE state, nothing on disk
	 * changes, and it is trivially reversible by reopening. Triggers an
	 * incremental build afterwards so any project that depended on a now-closed
	 * one gets its markers refreshed rather than showing a stale green state.
	 */
	public static ToolResult closeProjects(List<String> names) {
		if (names == null || names.isEmpty()) {
			return new ToolResult(false, "Missing required argument: projects");
		}
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		List<String> closed = new ArrayList<>();
		List<String> alreadyClosed = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		try {
			runBatched("BlueMind Doctor — fermeture de " + names.size() + " projet(s)", monitor -> {
				SubMonitor sub = SubMonitor.convert(monitor, names.size());
				for (String name : names) {
					sub.subTask(name);
					sub.worked(1);
					IProject p = ws.getRoot().getProject(name);
					if (!p.exists()) {
						errors.add(name + ": project not found in workspace");
						continue;
					}
					if (!p.isOpen()) {
						alreadyClosed.add(name);
						continue;
					}
					try {
						p.close(new NullProgressMonitor());
						closed.add(name);
					} catch (CoreException e) {
						errors.add(name + ": close failed — " + e.getMessage());
					}
				}
			}, buildMonitor -> {
				if (!closed.isEmpty()) {
					try {
						ws.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, buildMonitor);
					} catch (CoreException e) {
						errors.add("workspace build: " + e.getMessage());
					}
				}
			});
		} catch (CoreException e) {
			errors.add("batch close failed: " + e.getMessage());
		}
		waitForBuildJobs();

		boolean ok = errors.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Close projects — ").append(ok ? "OK" : "ISSUES").append("\n\n");
		sb.append("Closed: ").append(closed.size()).append(" | Already closed: ").append(alreadyClosed.size())
				.append("\n");
		appendList(sb, "Closed", closed);
		appendList(sb, "Already closed", alreadyClosed);
		appendList(sb, "Errors", errors);
		appendJsonBlock(sb, Map.of("closed", closed, "alreadyClosed", alreadyClosed, "errors", errors));
		return new ToolResult(ok, sb.toString());
	}

	/** Expected leftovers of a project git never saw finished — anything else refuses. */
	private static final Set<String> SHELL_RESIDUE_TOP = Set.of(".project", ".classpath", ".settings", "bin",
			"target");

	/**
	 * Top-level entries of a project directory that are NOT expected shell residue.
	 * Empty means safe to remove; any hit might be a bundle created and not yet
	 * {@code git add}-ed, so the caller must refuse on it rather than guess.
	 */
	private static List<String> unexpectedResidue(Path projectDir) {
		List<String> unexpected = new ArrayList<>();
		if (projectDir == null || !Files.isDirectory(projectDir)) {
			return unexpected;
		}
		try (java.util.stream.Stream<Path> stream = Files.list(projectDir)) {
			for (Path entry : (Iterable<Path>) stream::iterator) {
				String name = entry.getFileName().toString();
				if (!SHELL_RESIDUE_TOP.contains(name)) {
					unexpected.add(name);
				}
			}
		} catch (IOException e) {
			unexpected.add("(could not list directory: " + e.getMessage() + ")");
		}
		return unexpected;
	}

	/**
	 * Remove project(s) whose directory git recognises no tracked file under — a
	 * stale-project:untracked-shell fact (see the doctor's classify()). All-or-nothing,
	 * same shape as the codegen delete guard: one project failing a guard cancels the
	 * whole list, because a set like this is only coherent as a set.
	 *
	 * <p>Two guards, both refusing on doubt rather than proceeding: {@code tracked} must
	 * be exactly FALSE (a null — no repository resolved — refuses just like TRUE does),
	 * and the directory's top-level entries must be nothing but expected residue. When
	 * both hold, {@link IProject#delete(boolean, boolean, IProgressMonitor)} with
	 * {@code deleteContent=true} removes the workspace entry AND the residue in one
	 * call — safe exactly because the guard already established there is nothing else
	 * there.
	 */
	public static ToolResult removeProjects(List<String> names) {
		if (names == null || names.isEmpty()) {
			return new ToolResult(false, "Missing required argument: projects");
		}
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		Map<String, IProject> toRemove = new LinkedHashMap<>();
		List<String> refused = new ArrayList<>();

		try (GitIndexReader git = new GitIndexReader()) {
			for (String name : names) {
				IProject p = ws.getRoot().getProject(name);
				if (!p.exists()) {
					refused.add(name + ": not in the workspace");
					continue;
				}
				IPath location = p.getLocation();
				Path dir = location == null ? null : Path.of(location.toOSString());
				Boolean tracked = dir == null ? null : git.hasTrackedFiles(p, dir);
				if (!Boolean.FALSE.equals(tracked)) {
					refused.add(name + ": " + (tracked == null ? "git status could not be resolved"
							: "has at least one tracked file") + " — refusing");
					continue;
				}
				List<String> unexpected = unexpectedResidue(dir);
				if (!unexpected.isEmpty()) {
					refused.add(name + ": unexpected content beyond shell residue — "
							+ String.join(", ", unexpected));
					continue;
				}
				toRemove.put(name, p);
			}
		}

		if (!refused.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append("# Remove projects — REFUSED\n\n");
			sb.append("NOTHING was removed: the list is all-or-nothing and ").append(refused.size())
					.append(" project(s) failed a guard.\n");
			appendList(sb, "Refused", refused);
			appendJsonBlock(sb, Map.of("removed", List.of(), "refused", refused));
			return new ToolResult(false, sb.toString());
		}
		if (toRemove.isEmpty()) {
			return new ToolResult(false, "Nothing to do: no project name resolved.");
		}

		WorkspaceConsent.Decision consent = WorkspaceConsent.checkProjects(
				"Claude Code (doctor) wants to remove " + toRemove.size()
						+ " untracked shell project(s) from the workspace AND delete their on-disk residue"
						+ " (.project, .classpath, .settings/, bin/, target/). Allow?");
		if (!consent.allowed()) {
			return new ToolResult(false, "Refused: " + consent.reason());
		}

		List<String> removed = new ArrayList<>();
		for (Map.Entry<String, IProject> entry : toRemove.entrySet()) {
			try {
				entry.getValue().delete(true, true, new NullProgressMonitor());
				removed.add(entry.getKey());
			} catch (CoreException e) {
				refused.add(entry.getKey() + ": delete failed — " + e.getMessage());
			}
		}

		boolean ok = refused.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Remove projects — ").append(ok ? "OK" : "ISSUES").append("\n\n");
		sb.append("Removed: ").append(removed.size()).append("\n");
		appendCapped(sb, "Removed", removed, 100);
		appendList(sb, "Errors", refused);
		appendJsonBlock(sb, Map.of("removed", removed, "refused", refused));
		return new ToolResult(ok, sb.toString());
	}

	/**
	 * Atomic open+import+refresh batch with auto-build suspended, then the cleans and
	 * a single build. The doctor computes every workspace remedy of a pass and calls
	 * this once instead of paying one build per remedy — up to four builds before.
	 * Auto-build is restored in a finally, and a net at start()/stop() covers a crash
	 * mid-batch.
	 *
	 * <p>Consent is required only when {@code open} or {@code import} is non-empty.
	 * {@code refresh_projects} and {@code clean_projects} are gated by nothing on
	 * their own, and rightly so: they invalidate build state, they do not change what
	 * belongs to the workspace. Routing them through the batch must not drag them
	 * under a consent they never needed.
	 */
	public static ToolResult applyWorkspaceBatch(List<String> openNames, List<String> importNames,
			List<String> refreshNames, List<String> cleanNames, List<String> closeNames,
			List<String> deleteGeneratedPaths, String rootPath, boolean build) {
		List<String> open = openNames == null ? List.of() : openNames;
		List<String> toImport = importNames == null ? List.of() : importNames;
		List<String> toRefresh = refreshNames == null ? List.of() : refreshNames;
		List<String> toClean = cleanNames == null ? List.of() : cleanNames;
		List<String> toClose = closeNames == null ? List.of() : closeNames;
		List<String> toDelete = deleteGeneratedPaths == null ? List.of() : deleteGeneratedPaths;
		if (open.isEmpty() && toImport.isEmpty() && toRefresh.isEmpty() && toClean.isEmpty() && toClose.isEmpty()
				&& toDelete.isEmpty()) {
			return new ToolResult(false,
					"Nothing to do: provide 'open', 'import', 'refresh', 'clean', 'close' and/or 'deleteGenerated'.");
		}

		Map<String, Path> scannedDisk = Map.of();
		if (!toImport.isEmpty()) {
			Path root = resolveRoot(rootPath);
			if (root == null) {
				return new ToolResult(false,
						"'import' given but no 'path' and the BlueMind global POM was not found to derive the repo root.");
			}
			if (!Files.isDirectory(root)) {
				return new ToolResult(false, "Root path is not a directory: " + root);
			}
			try {
				scannedDisk = scanDiskProjects(root);
			} catch (IOException e) {
				return new ToolResult(false, "scan failed: " + e.getMessage());
			}
		}
		final Map<String, Path> diskByName = scannedDisk;

		boolean changesMembership = !open.isEmpty() || !toImport.isEmpty();
		if (changesMembership) {
			WorkspaceConsent.Decision consent = WorkspaceConsent.checkProjects(
					"Claude Code (doctor) wants to open " + open.size() + " and import " + toImport.size()
							+ " project(s) in one batch (auto-build is suspended for the batch, then restored). Allow?");
			if (!consent.allowed()) {
				return new ToolResult(false, "Batch refused: " + consent.reason());
			}
		}

		IWorkspace ws = ResourcesPlugin.getWorkspace();
		List<String> opened = new ArrayList<>();
		List<String> alreadyOpen = new ArrayList<>();
		List<String> imported = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		List<String> notOnDisk = new ArrayList<>();
		List<String> refreshed = new ArrayList<>();
		List<String> cleaned = new ArrayList<>();
		List<String> closed = new ArrayList<>();
		List<String> alreadyClosed = new ArrayList<>();
		List<String> deleted = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		// Deletions are decided BEFORE anything is touched, and it is all or nothing: one
		// file that fails a guard cancels the whole list. The doctor asks to remove a
		// codegen output whose input no longer exists, and a set like that is only
		// coherent as a set — dropping half of it would leave the project in a state
		// nobody asked for.
		DeletePlan deletePlan = planDeletes(toDelete, resolveRoot(rootPath));
		final List<IFile> deletable = deletePlan.refusals().isEmpty() ? deletePlan.files() : List.of();

		int mutations = open.size() + toImport.size() + toRefresh.size() + toClose.size() + deletable.size();
		Boolean previousAutoBuild = null;
		try {
			previousAutoBuild = suspendAutoBuild();
			runBatched("BlueMind Doctor — lot de " + (mutations + toClean.size()) + " remède(s)", monitor -> {
				SubMonitor sub = SubMonitor.convert(monitor, mutations);
				// First: the deletions, so the refreshes below see the final tree.
				for (IFile file : deletable) {
					sub.subTask(file.getName());
					sub.worked(1);
					try {
						file.delete(false, new NullProgressMonitor());
						deleted.add(file.getFullPath().toString());
					} catch (CoreException e) {
						errors.add(file.getFullPath() + ": delete failed — " + e.getMessage());
					}
				}
				for (String name : open) {
					sub.subTask(name);
					sub.worked(1);
					IProject p = ws.getRoot().getProject(name);
					if (!p.exists()) {
						errors.add(name + ": project not found in workspace");
					} else if (p.isOpen()) {
						alreadyOpen.add(name);
					} else {
						try {
							p.open(new NullProgressMonitor());
							opened.add(name);
						} catch (CoreException e) {
							errors.add(name + ": open failed — " + e.getMessage());
						}
					}
				}
				for (String name : toImport) {
					sub.subTask(name);
					sub.worked(1);
					Path dotProject = diskByName.get(name);
					if (dotProject == null) {
						notOnDisk.add(name);
						continue;
					}
					importOne(ws, dotProject, imported, skipped, errors);
				}
				// Refresh last: a project opened or imported just above is refreshed on the
				// way in, and one named here may well be one of them.
				for (String name : toRefresh) {
					sub.subTask(name);
					sub.worked(1);
					IProject p = ws.getRoot().getProject(name);
					if (!p.exists()) {
						errors.add(name + ": project not found in workspace");
					} else if (!p.isOpen()) {
						errors.add(name + ": project is closed — open it instead of refreshing it");
					} else {
						try {
							p.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
							refreshed.add(name);
						} catch (CoreException e) {
							errors.add(name + ": refresh failed — " + e.getMessage());
						}
					}
				}
				// Close last: nothing above should still be acting on a project once it is
				// closed (open/import/refresh already ran).
				for (String name : toClose) {
					sub.subTask(name);
					sub.worked(1);
					IProject p = ws.getRoot().getProject(name);
					if (!p.exists()) {
						errors.add(name + ": project not found in workspace");
					} else if (!p.isOpen()) {
						alreadyClosed.add(name);
					} else {
						try {
							p.close(new NullProgressMonitor());
							closed.add(name);
						} catch (CoreException e) {
							errors.add(name + ": close failed — " + e.getMessage());
						}
					}
				}
			}, buildMonitor -> {
				SubMonitor sub = SubMonitor.convert(buildMonitor, toClean.size() + 1);
				// Cleans run outside the workspace operation: a build cannot be nested in
				// IWorkspace.run. Discarding build state only — the single build below
				// rebuilds the cleaned projects along with everything the batch touched.
				for (String name : toClean) {
					IProject p = ws.getRoot().getProject(name);
					if (!p.exists() || !p.isOpen()) {
						errors.add(name + ": cannot clean — not in the workspace or closed");
						sub.worked(1);
						continue;
					}
					try {
						p.build(IncrementalProjectBuilder.CLEAN_BUILD, sub.split(1));
						cleaned.add(name);
					} catch (CoreException e) {
						errors.add(name + ": clean failed — " + e.getMessage());
					}
				}
				if (build && !(opened.isEmpty() && imported.isEmpty() && refreshed.isEmpty() && cleaned.isEmpty()
						&& closed.isEmpty() && deleted.isEmpty())) {
					try {
						ws.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, sub.split(1));
					} catch (CoreException e) {
						errors.add("workspace build: " + e.getMessage());
					}
				}
			});
		} catch (CoreException e) {
			errors.add("batch failed: " + e.getMessage());
		} finally {
			restoreAutoBuild(previousAutoBuild);
		}
		waitForBuildJobs();

		// A project can legitimately appear in two remedy lists (refreshed then cleaned);
		// a set keeps its markers from being counted twice.
		Set<String> touched = new TreeSet<>(opened);
		touched.addAll(imported);
		touched.addAll(refreshed);
		touched.addAll(cleaned);
		List<String> compileErrors = new ArrayList<>();
		for (String name : touched) {
			IProject p = ws.getRoot().getProject(name);
			try {
				for (IMarker m : p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
					int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
					if (sev == IMarker.SEVERITY_ERROR) {
						compileErrors.add(formatMarker(p, m, sev));
					}
				}
			} catch (CoreException e) {
				errors.add(name + ": could not read markers — " + e.getMessage());
			}
		}

		boolean ok = errors.isEmpty() && compileErrors.isEmpty() && deletePlan.refusals().isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Apply workspace batch — ").append(ok ? "OK" : "ISSUES").append("\n\n");
		sb.append("Opened: ").append(opened.size()).append(" | Imported: ").append(imported.size())
				.append(" | Refreshed: ").append(refreshed.size()).append(" | Cleaned: ").append(cleaned.size())
				.append(" | Closed: ").append(closed.size())
				.append(" | Deleted: ").append(deleted.size()).append(" | Already open: ").append(alreadyOpen.size())
				.append(" | Already closed: ").append(alreadyClosed.size())
				.append(" | Build: ").append(build)
				.append(" | Consent: ").append(changesMembership ? "required" : "not needed (no membership change)")
				.append("\n");
		appendCapped(sb, "Opened", opened, 100);
		appendCapped(sb, "Imported", imported, 100);
		appendCapped(sb, "Refreshed", refreshed, 100);
		appendCapped(sb, "Cleaned", cleaned, 100);
		appendCapped(sb, "Closed", closed, 100);
		appendCapped(sb, "Deleted — generated, untracked", deleted, 100);
		if (!deletePlan.refusals().isEmpty()) {
			sb.append("\nNOTHING was deleted: the list is all-or-nothing and ")
					.append(deletePlan.refusals().size()).append(" path(s) failed a guard.\n");
		}
		appendList(sb, "Refused deletes", deletePlan.refusals());
		appendCapped(sb, "Already open", alreadyOpen, 100);
		appendCapped(sb, "Already closed", alreadyClosed, 100);
		appendCapped(sb, "Already present", skipped, 100);
		appendCapped(sb, "Not found on disk", notOnDisk, 100);
		appendList(sb, "Errors", errors);
		appendCapped(sb, "Compile errors", compileErrors, 50);

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("opened", opened);
		json.put("imported", imported);
		json.put("refreshed", refreshed);
		json.put("cleaned", cleaned);
		json.put("closed", closed);
		json.put("deleted", deleted);
		json.put("refusedDeletes", deletePlan.refusals());
		json.put("alreadyOpen", alreadyOpen);
		json.put("alreadyClosed", alreadyClosed);
		json.put("notOnDisk", notOnDisk);
		json.put("consentRequired", changesMembership);
		json.put("errors", errors);
		appendJsonBlock(sb, json);
		return new ToolResult(ok, sb.toString());
	}

	private record DeletePlan(List<IFile> files, List<String> refusals) {
	}

	/**
	 * The guard on the only write the doctor ever makes to disk. Three conditions, all
	 * three required, and all three checked <em>here</em> — by the code that deletes,
	 * never by the caller that asks:
	 *
	 * <ol>
	 * <li>the path sits under a {@code kind="src"} folder of its project whose name says
	 * it is generated: nobody writes there by hand, so nothing there is anyone's work;</li>
	 * <li>git does not track the file: a tracked file is somebody's commit, whatever the
	 * folder is called. "git cannot answer" counts as tracked — it is a refusal;</li>
	 * <li>the file exists as an {@link IFile} under the repo root.</li>
	 * </ol>
	 *
	 * <p>Deletion goes through {@link IFile#delete} rather than the filesystem: removing
	 * a file behind Eclipse's back would recreate exactly the desynchronisation the
	 * doctor is there to repair.
	 */
	private static DeletePlan planDeletes(List<String> paths, Path root) {
		List<IFile> files = new ArrayList<>();
		List<String> refusals = new ArrayList<>();
		if (paths.isEmpty()) {
			return new DeletePlan(files, refusals);
		}
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		try (GitIndexReader git = new GitIndexReader()) {
			for (String raw : paths) {
				String portable = raw.startsWith("/") ? raw.substring(1) : raw;
				IPath path = IPath.fromPortableString(portable);
				if (path.segmentCount() < 2) {
					refusals.add(raw + ": not a workspace-relative file path");
					continue;
				}
				IProject project = ws.getRoot().getProject(path.segment(0));
				if (!project.exists() || !project.isOpen()) {
					refusals.add(raw + ": project not in the workspace, or closed");
					continue;
				}
				IFile file = project.getFile(path.removeFirstSegments(1));
				if (!file.exists()) {
					refusals.add(raw + ": no such file in the workspace");
					continue;
				}
				IPath location = file.getLocation();
				Path absolute = location == null ? null : Path.of(location.toOSString());
				if (absolute == null || root == null || !absolute.startsWith(root)) {
					refusals.add(raw + ": outside the repo root");
					continue;
				}
				if (!inGeneratedSourceFolder(project, file.getFullPath())) {
					refusals.add(raw + ": not under a generated kind=\"src\" folder of " + project.getName());
					continue;
				}
				Boolean tracked = git.isTracked(file, absolute);
				if (tracked == null) {
					refusals.add(raw + ": git could not say whether it is tracked");
					continue;
				}
				if (tracked) {
					refusals.add(raw + ": tracked by git");
					continue;
				}
				files.add(file);
			}
		}
		return new DeletePlan(files, refusals);
	}

	private static boolean inGeneratedSourceFolder(IProject project, IPath fullPath) {
		IJavaProject java = JavaCore.create(project);
		if (java == null || !java.exists()) {
			return false;
		}
		try {
			for (IClasspathEntry entry : java.getRawClasspath()) {
				if (entry.getEntryKind() != IClasspathEntry.CPE_SOURCE) {
					continue;
				}
				IPath folder = entry.getPath();
				String last = folder.lastSegment();
				if (last == null || !last.toLowerCase().contains(GENERATED_HINT)) {
					continue;
				}
				if (folder.isPrefixOf(fullPath) && !folder.equals(fullPath)) {
					return true;
				}
			}
		} catch (JavaModelException e) {
			return false;
		}
		return false;
	}

	/**
	 * Turn auto-build off, saving the previous value to a preference first so a
	 * crash before {@link #restoreAutoBuild} can be recovered at plugin start/stop.
	 * Returns the previous value to restore, or null if it was already off.
	 */
	private static Boolean suspendAutoBuild() throws CoreException {
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		IWorkspaceDescription desc = ws.getDescription();
		boolean previous = desc.isAutoBuilding();
		if (!previous) {
			return null;
		}
		Activator.getDefault().getPreferenceStore().setValue(Activator.PREF_AUTOBUILD_SAVED,
				Boolean.toString(previous));
		desc.setAutoBuilding(false);
		ws.setDescription(desc);
		return previous;
	}

	private static void restoreAutoBuild(Boolean previous) {
		if (previous == null) {
			return;
		}
		try {
			IWorkspace ws = ResourcesPlugin.getWorkspace();
			IWorkspaceDescription desc = ws.getDescription();
			if (desc.isAutoBuilding() != previous) {
				desc.setAutoBuilding(previous);
				ws.setDescription(desc);
			}
		} catch (CoreException ignored) {
			// best effort — the start/stop net will catch a leftover state
		} finally {
			Activator.getDefault().getPreferenceStore().setValue(Activator.PREF_AUTOBUILD_SAVED, "");
		}
	}

	/**
	 * Safety net called from the Activator start() and stop(): if a batch suspended
	 * auto-build and never restored it (hard crash), put it back to the saved value.
	 */
	public static void restoreSuspendedAutoBuild() {
		if (Activator.getDefault() == null) {
			return;
		}
		String saved = Activator.getDefault().getPreferenceStore().getString(Activator.PREF_AUTOBUILD_SAVED);
		if (saved == null || saved.isBlank()) {
			return;
		}
		try {
			IWorkspace ws = ResourcesPlugin.getWorkspace();
			IWorkspaceDescription desc = ws.getDescription();
			desc.setAutoBuilding(Boolean.parseBoolean(saved));
			ws.setDescription(desc);
		} catch (CoreException ignored) {
			// best effort
		} finally {
			Activator.getDefault().getPreferenceStore().setValue(Activator.PREF_AUTOBUILD_SAVED, "");
		}
	}

	private static Path resolveRoot(String rootPath) {
		if (rootPath != null && !rootPath.isBlank()) {
			return Path.of(rootPath);
		}
		return PomPropertyReader.findRepoRoot().orElse(null);
	}

	public static ToolResult importProjects(String rootPath) {
		Path root = resolveRoot(rootPath);
		if (root == null) {
			return new ToolResult(false,
					"No 'path' given and the BlueMind global POM was not found to derive the repo root.");
		}
		if (!Files.isDirectory(root)) {
			return new ToolResult(false, "Root path is not a directory: " + root);
		}

		WorkspaceConsent.Decision consent = WorkspaceConsent.checkProjects(
				"Claude Code wants to import Eclipse projects found on disk under " + root
						+ " that are not yet in the workspace. Allow?");
		if (!consent.allowed()) {
			return new ToolResult(false, "Import refused: " + consent.reason());
		}

		IWorkspace ws = ResourcesPlugin.getWorkspace();
		List<String> imported = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		try {
			runBatched("BlueMind Doctor — import des projets sur disque", monitor -> {
				SubMonitor sub = SubMonitor.convert(monitor);
				try {
					Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
						@Override
						public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
							String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
							if (SKIP_DIRS.contains(dirName)) {
								return FileVisitResult.SKIP_SUBTREE;
							}
							Path dotProject = dir.resolve(".project");
							if (Files.isRegularFile(dotProject)) {
								sub.subTask(dirName);
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
			}, buildMonitor -> {
				if (!imported.isEmpty()) {
					try {
						ws.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, buildMonitor);
					} catch (CoreException e) {
						errors.add("workspace build: " + e.getMessage());
					}
				}
			});
		} catch (CoreException e) {
			errors.add("batch import failed: " + e.getMessage());
		}
		waitForBuildJobs();

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

	/**
	 * Run project mutations (open/create/delete) as ONE atomic workspace operation.
	 * A bare {@code IProject.open/create/delete} is otherwise its own top-level
	 * operation that broadcasts a POST_CHANGE delta on completion, so PDE/JDT recompute
	 * once per project (seconds each) — opening hundreds of projects took tens of
	 * minutes even with auto-build suspended. Wrapping the whole loop collapses it to a
	 * single delta and one recompute; callers run their single build afterwards.
	 * {@code AVOID_UPDATE} keeps the operation from scheduling auto-build.
	 *
	 * <p>Runs inside a non-user {@link Job} (status bar + Progress view, non-blocking)
	 * so its {@link SubMonitor} surfaces the task name and per-project subtask, then
	 * blocks on {@code join()} so the caller still gets a synchronous result. The single
	 * {@code IWorkspace.run} is preserved — progress is reported <em>inside</em> it, so
	 * the delta stays batched (no per-project storm).
	 *
	 * <p>The optional {@code buildPhase} — typically the workspace build that follows the
	 * mutations — runs in the <em>same</em> Job, after {@code ws.run}, under its own slice
	 * of the monitor. Keeping it here means the long build shows up as "BlueMind Doctor"
	 * in the Progress view rather than a generic "Building" job. It runs after the batched
	 * mutations, so lists the mutation body populated are already final when it reads them.
	 */
	private static void runBatched(String taskName, IWorkspaceRunnable body) throws CoreException {
		runBatched(taskName, body, null);
	}

	private static void runBatched(String taskName, IWorkspaceRunnable body, ICoreRunnable buildPhase)
			throws CoreException {
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		CoreException[] failure = new CoreException[1];
		Job job = Job.create(taskName, monitor -> {
			SubMonitor root = SubMonitor.convert(monitor, 100);
			try {
				ws.run(body, ws.getRoot(), IWorkspace.AVOID_UPDATE, root.split(buildPhase == null ? 100 : 70));
				if (buildPhase != null) {
					buildPhase.run(root.split(30));
				}
			} catch (CoreException e) {
				failure[0] = e;
			}
			return Status.OK_STATUS;
		});
		job.setUser(false);
		job.schedule();
		try {
			job.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "batch interrupted", e));
		}
		if (failure[0] != null) {
			throw failure[0];
		}
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

	private record MovedProject(String name, Path diskDotProject) {
	}

	public static ToolResult syncProjects(String rootPath, boolean apply, boolean removeObsolete) {
		Path root = resolveRoot(rootPath);
		if (root == null) {
			return new ToolResult(false,
					"No 'path' given and the BlueMind global POM was not found to derive the repo root.");
		}
		if (!Files.isDirectory(root)) {
			return new ToolResult(false, "Root path is not a directory: " + root);
		}

		Map<String, Path> diskProjects;
		try {
			diskProjects = scanDiskProjects(root);
		} catch (IOException e) {
			return new ToolResult(false, "scan failed: " + e.getMessage());
		}

		IWorkspace ws = ResourcesPlugin.getWorkspace();
		List<String> obsolete = new ArrayList<>();
		List<MovedProject> movedList = new ArrayList<>();
		List<String> movedReport = new ArrayList<>();

		try (GitIndexReader git = new GitIndexReader()) {
			for (IProject p : ws.getRoot().getProjects()) {
				IPath location = p.getLocation();
				if (location == null) {
					continue;
				}
				Path workspacePath = Path.of(location.toOSString());
				Path diskDotProject = diskProjects.remove(p.getName());
				if (diskDotProject != null) {
					Path diskDir = diskDotProject.getParent();
					if (!diskDir.equals(workspacePath)) {
						movedList.add(new MovedProject(p.getName(), diskDotProject));
						movedReport.add(p.getName() + ": " + workspacePath + " -> " + diskDir);
						continue;
					}
					// Same criterion as remove_projects (lot E1): a .project can still sit on
					// disk while the directory holds not one tracked file — a shell git would
					// not recognise from any commit either. Flagged here for visibility; the
					// disk residue cleanup that goes with it is remove_projects' job, not this
					// removal path (which never deletes content).
					if (Boolean.FALSE.equals(git.hasTrackedFiles(p, workspacePath))) {
						obsolete.add(p.getName());
					}
					continue;
				}
				// Never flag a project outside the scanned root as obsolete: other repos or
				// unrelated projects the user keeps in the same workspace are not our concern.
				if (workspacePath.startsWith(root)) {
					obsolete.add(p.getName());
				}
			}
		}
		List<String> toImport = new ArrayList<>(diskProjects.keySet());

		List<String> imported = new ArrayList<>();
		List<String> removedNames = new ArrayList<>();
		List<String> movedDone = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		boolean willRemove = removeObsolete && !obsolete.isEmpty();
		boolean hasChanges = !toImport.isEmpty() || !movedList.isEmpty() || willRemove;

		if (apply && hasChanges) {
			WorkspaceConsent.Decision consent = WorkspaceConsent.checkProjects(
					"Claude Code wants to sync workspace projects under " + root + ": import " + toImport.size()
							+ ", remove " + (willRemove ? obsolete.size() : 0) + " obsolete, move "
							+ movedList.size() + ". Allow?");
			if (!consent.allowed()) {
				return new ToolResult(false, "Sync refused: " + consent.reason());
			}

			try {
				runBatched("BlueMind Doctor — sync workspace", monitor -> {
					SubMonitor sub = SubMonitor.convert(monitor,
							(willRemove ? obsolete.size() : 0) + movedList.size() + diskProjects.size());
					if (willRemove) {
						for (String name : obsolete) {
							sub.subTask("remove " + name);
							sub.worked(1);
							try {
								ws.getRoot().getProject(name).delete(false, true, new NullProgressMonitor());
								removedNames.add(name);
							} catch (CoreException e) {
								errors.add(name + ": remove failed — " + e.getMessage());
							}
						}
					}
					for (MovedProject mp : movedList) {
						sub.subTask("move " + mp.name());
						sub.worked(1);
						try {
							ws.getRoot().getProject(mp.name()).delete(false, true, new NullProgressMonitor());
						} catch (CoreException e) {
							errors.add(mp.name() + ": move (remove old) failed — " + e.getMessage());
							continue;
						}
						importOne(ws, mp.diskDotProject(), movedDone, new ArrayList<>(), errors);
					}
					for (Path dotProject : diskProjects.values()) {
						sub.subTask("import " + dotProject.getFileName());
						sub.worked(1);
						importOne(ws, dotProject, imported, new ArrayList<>(), errors);
					}
				}, buildMonitor -> {
					try {
						ws.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, buildMonitor);
					} catch (CoreException e) {
						errors.add("workspace build: " + e.getMessage());
					}
				});
			} catch (CoreException e) {
				errors.add("batch sync failed: " + e.getMessage());
			}
			waitForBuildJobs();
		}

		List<String> compileErrors = new ArrayList<>();
		if (apply) {
			List<String> touched = new ArrayList<>(imported);
			touched.addAll(movedDone);
			for (String name : touched) {
				IProject p = ws.getRoot().getProject(name);
				try {
					for (IMarker m : p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
						int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
						if (sev == IMarker.SEVERITY_ERROR) {
							compileErrors.add(formatMarker(p, m, sev));
						}
					}
				} catch (CoreException e) {
					errors.add(name + ": could not read problem markers — " + e.getMessage());
				}
			}
		}

		boolean ok = errors.isEmpty() && compileErrors.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Sync projects — ").append(!apply ? "DRY-RUN" : ok ? "OK" : "ISSUES").append("\n\n");
		sb.append("Root: ").append(root).append("\n");
		if (!apply) {
			sb.append("Would import: ").append(toImport.size()).append(" | Would remove (obsolete): ")
					.append(obsolete.size()).append(" | Would move: ").append(movedList.size()).append("\n");
			appendCapped(sb, "Would be imported", toImport, 100);
			appendCapped(sb, "Would be removed — obsolete, content untouched", obsolete, 100);
			appendCapped(sb, "Would be moved", movedReport, 100);
		} else {
			sb.append("Imported: ").append(imported.size()).append(" | Removed: ").append(removedNames.size())
					.append(" | Moved: ").append(movedDone.size()).append("\n");
			appendCapped(sb, "Imported", imported, 100);
			appendCapped(sb, "Removed — obsolete, content untouched", removedNames, 100);
			appendCapped(sb, "Moved", movedDone, 100);
		}
		appendList(sb, "Errors", errors);
		appendCapped(sb, "Compile errors", compileErrors, 50);

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("root", root.toString());
		json.put("apply", apply);
		json.put("toImport", toImport);
		json.put("obsolete", obsolete);
		json.put("moved", movedReport);
		appendJsonBlock(sb, json);
		return new ToolResult(ok, sb.toString());
	}

	public static ToolResult listProjects(String scope) {
		List<String> lines = new ArrayList<>();
		List<Map<String, Object>> jsonProjects = projectsData(scope, lines);

		StringBuilder sb = new StringBuilder();
		sb.append("# Projects (").append(scope == null || scope.isBlank() ? "all" : scope).append(")\n\n");
		sb.append("Count: ").append(lines.size()).append("\n");
		appendCapped(sb, "Projects", lines, 300);
		appendJsonBlock(sb, Map.of("projects", jsonProjects));
		return new ToolResult(true, sb.toString());
	}

	/**
	 * Project facts, shared by {@code list_projects} and {@code doctor_snapshot}. The
	 * markdown lines are collected into {@code lines} when the caller wants them (the
	 * snapshot does not — it only ships the JSON).
	 */
	private static List<Map<String, Object>> projectsData(String scope, List<String> lines) {
		String sc = scope == null || scope.isBlank() ? "all" : scope.toLowerCase();
		Path root = resolveRoot(null);
		Map<String, List<String>> workingSets = workingSetMembership();
		IWorkspace ws = ResourcesPlugin.getWorkspace();

		List<Map<String, Object>> jsonProjects = new ArrayList<>();
		try (GitIndexReader git = new GitIndexReader()) {
			for (IProject p : ws.getRoot().getProjects()) {
				boolean open = p.isOpen();
				if ("closed".equals(sc) && open) {
					continue;
				}
				int errorCount = 0;
				int warningCount = 0;
				if (open) {
					try {
						for (IMarker m : p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
							int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
							if (sev == IMarker.SEVERITY_ERROR) {
								errorCount++;
							} else if (sev == IMarker.SEVERITY_WARNING) {
								warningCount++;
							}
						}
					} catch (CoreException ignored) {
						// reported as 0/0 — not fatal for a listing
					}
				}
				if ("errors".equals(sc) && errorCount == 0) {
					continue;
				}
				IPath location = p.getLocation();
				Path locationPath = location == null ? null : Path.of(location.toOSString());
				boolean missing = locationPath == null || !Files.exists(locationPath);
				String path = locationPath == null ? "(unknown)" : relativize(root, locationPath);
				List<String> sets = workingSets.getOrDefault(p.getName(), List.of());
				// null when git could not be resolved (no repo, bare, outside worktree) — a
				// caller that removes a project treats null as a refusal, never as untracked.
				Boolean tracked = locationPath == null ? null : git.hasTrackedFiles(p, locationPath);

				if (lines != null) {
					StringBuilder line = new StringBuilder();
					line.append(open ? "[open] " : "[closed] ").append(p.getName()).append(" — ").append(path);
					if (open) {
						line.append(" — errors: ").append(errorCount).append(", warnings: ").append(warningCount);
					}
					if (!sets.isEmpty()) {
						line.append(" — sets: ").append(String.join(", ", sets));
					}
					if (missing) {
						line.append(" — MISSING LOCATION");
					}
					if (Boolean.FALSE.equals(tracked)) {
						line.append(" — UNTRACKED (no file under git)");
					}
					lines.add(line.toString());
				}

				Map<String, Object> json = new LinkedHashMap<>();
				json.put("name", p.getName());
				json.put("path", path);
				json.put("open", open);
				json.put("errors", errorCount);
				json.put("warnings", warningCount);
				json.put("workingSets", sets);
				json.put("missingLocation", missing);
				json.put("tracked", tracked);
				jsonProjects.add(json);
			}
		}
		return jsonProjects;
	}

	public static ToolResult workspaceInfo() {
		Map<String, Object> json = workspaceInfoData();

		StringBuilder sb = new StringBuilder();
		sb.append("# Workspace info\n\n");
		sb.append("Workspace: ").append(json.get("workspace") == null ? "(unknown)" : json.get("workspace"))
				.append("\n");
		sb.append("Repo root: ").append(json.get("repoRoot") == null ? "(not found)" : json.get("repoRoot"))
				.append("\n");
		sb.append("Branch: ").append(json.get("branch") == null ? "(unknown)" : json.get("branch")).append("\n");
		sb.append("Plugin: ").append(json.get("pluginVersion")).append("\n");
		sb.append("Projects: ").append(json.get("openProjects")).append(" open, ").append(json.get("closedProjects"))
				.append(" closed\n");
		appendJsonBlock(sb, json);
		return new ToolResult(true, sb.toString());
	}

	/** Workspace facts, shared by {@code workspace_info} and {@code doctor_snapshot}. */
	private static Map<String, Object> workspaceInfoData() {
		Path root = resolveRoot(null);
		String branch = root == null ? null : GitBranchReader.currentBranch(root).orElse(null);
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		int openCount = 0;
		int closedCount = 0;
		for (IProject p : ws.getRoot().getProjects()) {
			if (p.isOpen()) {
				openCount++;
			} else {
				closedCount++;
			}
		}
		IPath wsLocation = ws.getRoot().getLocation();

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("workspace", wsLocation == null ? null : wsLocation.toOSString());
		json.put("repoRoot", root == null ? null : root.toString());
		json.put("branch", branch);
		// Reported so a doctor run can name the plugin build it talked to — a remedy that
		// silently does nothing is often an outdated plugin, not a wrong classification.
		json.put("pluginVersion", Activator.getDefault() == null ? null
				: Activator.getDefault().getBundle().getVersion().toString());
		json.put("openProjects", openCount);
		json.put("closedProjects", closedCount);
		return json;
	}

	/**
	 * PDE's own bundle state — the ground truth the doctor used to guess by parsing
	 * MANIFEST.MF off disk. Without names: the workspace models, no exports (that is
	 * every diagnostic pass, and the target platform's exported packages would be
	 * megabytes through a serialising server). With names: a lookup wherever the
	 * bundle lives, exports and imports included — which is how "neither in the
	 * workspace nor on disk, so a target bundle" gets answered.
	 */
	public static ToolResult bundleState(List<String> names) {
		boolean named = names != null && !names.isEmpty();
		Path root = resolveRoot(null);
		List<Map<String, Object>> bundles = new ArrayList<>();
		List<String> unknown = new ArrayList<>();

		if (named) {
			for (String name : new TreeSet<>(names)) {
				IPluginModelBase model = PluginRegistry.findModel(name);
				if (model != null) {
					bundles.add(bundleJson(model, root, true));
					continue;
				}
				// PluginRegistry has no model for a CLOSED project either — it is not
				// "neither workspace nor target platform", it is workspace-but-closed.
				// Without this a closed provider reads as unknown here while
				// list_projects/locate_type already call it [closed].
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
				if (project.exists() && !project.isOpen()) {
					Map<String, Object> json = new LinkedHashMap<>();
					json.put("name", name);
					json.put("source", "workspace");
					json.put("closed", true);
					json.put("resolved", false);
					bundles.add(json);
				} else {
					unknown.add(name);
				}
			}
		} else {
			IPluginModelBase[] models = PluginRegistry.getWorkspaceModels();
			for (IPluginModelBase model : models) {
				bundles.add(bundleJson(model, root, false));
			}
			bundles.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
		}

		int unresolved = 0;
		int invisible = 0;
		List<String> lines = new ArrayList<>();
		for (Map<String, Object> b : bundles) {
			boolean resolved = Boolean.TRUE.equals(b.get("resolved"));
			@SuppressWarnings("unchecked")
			List<String> meta = (List<String>) b.get("metadataInvisible");
			if (!resolved) {
				unresolved++;
			}
			if (meta != null && !meta.isEmpty()) {
				invisible++;
			}
			if (resolved && (meta == null || meta.isEmpty())) {
				continue;
			}
			StringBuilder line = new StringBuilder();
			line.append(b.get("name")).append(" [").append(b.get("source")).append("]");
			line.append(resolved ? " resolved" : " NOT RESOLVED");
			if (Boolean.TRUE.equals(b.get("closed"))) {
				line.append(" — CLOSED PROJECT");
			}
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> errs = (List<Map<String, Object>>) b.get("resolverErrors");
			if (errs != null && !errs.isEmpty()) {
				line.append(" — ").append(errs.size()).append(" resolver error(s): ").append(errs.get(0).get("type"))
						.append(" ").append(errs.get(0).get("data"));
			}
			if (meta != null && !meta.isEmpty()) {
				line.append(" — METADATA INVISIBLE TO ECLIPSE: ").append(String.join(", ", meta));
			}
			lines.add(line.toString());
		}

		StringBuilder sb = new StringBuilder();
		sb.append("# Bundle state — ").append(bundles.size()).append(" bundle(s)\n\n");
		sb.append("Scope: ").append(named ? "named" : "workspace models").append(" | Unresolved: ").append(unresolved)
				.append(" | Metadata invisible: ").append(invisible).append("\n");
		appendCapped(sb, "Bundles needing attention", lines, 100);
		appendList(sb, "Unknown to PDE (neither workspace nor target platform)", unknown);
		appendJsonBlock(sb, Map.of("bundles", bundles, "unknown", unknown));
		return new ToolResult(true, sb.toString());
	}

	private static Map<String, Object> bundleJson(IPluginModelBase model, Path root, boolean includePackages) {
		BundleDescription desc = model.getBundleDescription();
		IResource resource = model.getUnderlyingResource();
		IProject project = resource == null ? null : resource.getProject();

		Map<String, Object> json = new LinkedHashMap<>();
		String name = desc != null ? desc.getSymbolicName()
				: model.getPluginBase() == null ? null : model.getPluginBase().getId();
		json.put("name", name);
		// getUnderlyingResource() != null is what distinguishes a project in this
		// workspace from a bundle served by the target platform.
		json.put("source", resource == null ? "target" : "workspace");
		json.put("version", desc == null ? null : desc.getVersion().toString());
		json.put("project", project == null ? null : project.getName());
		json.put("open", project == null ? null : project.isOpen());
		json.put("resolved", desc != null && desc.isResolved());
		json.put("resolverErrors", resolverErrorsJson(desc));
		json.put("requires", requiresJson(desc));
		if (includePackages) {
			json.put("exports", exportedPackages(desc));
			json.put("imports", importedPackages(desc));
		}
		appendMetadataVisibility(json, project, root);
		return json;
	}

	/**
	 * The fact the doctor could not see before: a PDE metadata file sitting on disk
	 * while Eclipse's resource tree does not know about it. Deliberately NOT
	 * {@code isSynchronized(DEPTH_INFINITE)} — Maven writes into {@code target/}
	 * outside Eclipse constantly, so that would report half the workspace as stale.
	 */
	private static void appendMetadataVisibility(Map<String, Object> json, IProject project, Path root) {
		IPath location = project == null ? null : project.getLocation();
		Path dir = location == null ? null : Path.of(location.toOSString());
		boolean outsideRoot = dir != null && (root == null || !dir.startsWith(root));
		json.put("locationOutsideRoot", project == null ? null : outsideRoot);
		// Never an absolute path: a doctor report must be transmissible as-is, so a
		// project outside the scanned root contributes no path at all.
		json.put("path", dir == null || outsideRoot ? null : relativize(root, dir));
		if (project == null || !project.isOpen() || dir == null || outsideRoot) {
			// Closed: Eclipse legitimately sees no resources, and 'open' is the remedy —
			// not a metadata problem. Outside the scanned root: claim nothing about it.
			json.put("metadataInvisible", null);
			return;
		}
		List<String> invisible = new ArrayList<>();
		for (String rel : PDE_METADATA_FILES) {
			if (Files.isRegularFile(dir.resolve(rel)) && !project.getFile(rel).exists()) {
				invisible.add(rel);
			}
		}
		json.put("metadataInvisible", invisible);
	}

	private static List<Map<String, Object>> resolverErrorsJson(BundleDescription desc) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (desc == null) {
			return out;
		}
		// The State is reachable from the description itself — no internal PDE class needed.
		State state = desc.getContainingState();
		if (state == null) {
			return out;
		}
		for (ResolverError err : state.getResolverErrors(desc)) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("type", resolverErrorType(err.getType()));
			json.put("data", err.getData());
			VersionConstraint constraint = err.getUnsatisfiedConstraint();
			json.put("constraint", constraint == null ? null : constraint.getName());
			json.put("range", constraint == null || constraint.getVersionRange() == null ? null
					: constraint.getVersionRange().toString());
			out.add(json);
		}
		return out;
	}

	private static String resolverErrorType(int type) {
		switch (type) {
		case ResolverError.MISSING_IMPORT_PACKAGE:
			return "MISSING_IMPORT_PACKAGE";
		case ResolverError.MISSING_REQUIRE_BUNDLE:
			return "MISSING_REQUIRE_BUNDLE";
		case ResolverError.MISSING_FRAGMENT_HOST:
			return "MISSING_FRAGMENT_HOST";
		case ResolverError.SINGLETON_SELECTION:
			return "SINGLETON_SELECTION";
		case ResolverError.FRAGMENT_CONFLICT:
			return "FRAGMENT_CONFLICT";
		case ResolverError.IMPORT_PACKAGE_USES_CONFLICT:
			return "IMPORT_PACKAGE_USES_CONFLICT";
		case ResolverError.REQUIRE_BUNDLE_USES_CONFLICT:
			return "REQUIRE_BUNDLE_USES_CONFLICT";
		case ResolverError.DISABLED_BUNDLE:
			return "DISABLED_BUNDLE";
		case ResolverError.PLATFORM_FILTER:
			return "PLATFORM_FILTER";
		case ResolverError.MISSING_EXECUTION_ENVIRONMENT:
			return "MISSING_EXECUTION_ENVIRONMENT";
		case ResolverError.NO_NATIVECODE_MATCH:
			return "NO_NATIVECODE_MATCH";
		case ResolverError.INVALID_NATIVECODE_PATHS:
			return "INVALID_NATIVECODE_PATHS";
		default:
			// Permission/policy errors and anything PDE adds later: the raw code is still
			// actionable in a report, and unknown-but-named beats silently dropping it.
			return "OTHER_0x" + Integer.toHexString(type);
		}
	}

	private static List<Map<String, Object>> requiresJson(BundleDescription desc) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (desc == null) {
			return out;
		}
		for (BundleSpecification spec : desc.getRequiredBundles()) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("name", spec.getName());
			json.put("range", spec.getVersionRange() == null ? null : spec.getVersionRange().toString());
			json.put("optional", spec.isOptional());
			json.put("reexport", spec.isExported());
			json.put("resolved", spec.getSupplier() != null);
			out.add(json);
		}
		return out;
	}

	private static List<String> exportedPackages(BundleDescription desc) {
		if (desc == null) {
			return List.of();
		}
		Set<String> out = new TreeSet<>();
		for (ExportPackageDescription export : desc.getExportPackages()) {
			out.add(export.getName());
		}
		return new ArrayList<>(out);
	}

	private static List<String> importedPackages(BundleDescription desc) {
		if (desc == null) {
			return List.of();
		}
		Set<String> out = new TreeSet<>();
		for (ImportPackageSpecification imported : desc.getImportPackages()) {
			out.add(imported.getName());
		}
		return new ArrayList<>(out);
	}

	/**
	 * The doctor's whole read for one pass in a single round-trip. The MCP server
	 * serialises calls on an instance, so four separate reads per pass add up in wall
	 * clock and multiply by the number of passes. Pure aggregate of facts — the
	 * classification and the choice of remedy stay in the script.
	 */
	public static ToolResult doctorSnapshot(String severity, boolean waitForBuild, List<String> extraBundles) {
		Map<String, Object> workspace = workspaceInfoData();
		Quiescence quiescence = waitForBuild ? settle() : new Quiescence(activeFamilies().isEmpty(), 0);
		// problemsData would settle again; it has already been done, and the result is
		// reported rather than assumed.
		ProblemsData problems = problemsData(List.of(), severity, false);
		List<Map<String, Object>> projects = projectsData("all", null);

		Path root = resolveRoot(null);
		List<Map<String, Object>> bundles = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (IPluginModelBase model : PluginRegistry.getWorkspaceModels()) {
			Map<String, Object> json = bundleJson(model, root, false);
			bundles.add(json);
			if (json.get("name") != null) {
				seen.add(String.valueOf(json.get("name")));
			}
		}
		List<String> unknown = new ArrayList<>();
		if (extraBundles != null) {
			for (String name : new TreeSet<>(extraBundles)) {
				if (seen.contains(name)) {
					continue;
				}
				IPluginModelBase model = PluginRegistry.findModel(name);
				if (model == null) {
					unknown.add(name);
				} else {
					bundles.add(bundleJson(model, root, true));
				}
			}
		}
		bundles.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));

		int unresolved = 0;
		int invisible = 0;
		for (Map<String, Object> b : bundles) {
			if (!Boolean.TRUE.equals(b.get("resolved"))) {
				unresolved++;
			}
			Object meta = b.get("metadataInvisible");
			if (meta instanceof List<?> list && !list.isEmpty()) {
				invisible++;
			}
		}

		UnresolvedTypes types = unresolvedTypes(problems.markers(), root);

		StringBuilder sb = new StringBuilder();
		sb.append("# Doctor snapshot\n\n");
		sb.append("Branch: ").append(workspace.get("branch")).append(" | Plugin: ")
				.append(workspace.get("pluginVersion")).append("\n");
		sb.append("Projects: ").append(workspace.get("openProjects")).append(" open, ")
				.append(workspace.get("closedProjects")).append(" closed\n");
		sb.append("Markers: ").append(problems.errorCount()).append(" error(s), ").append(problems.warningCount())
				.append(" warning(s)\n");
		sb.append("Bundles: ").append(bundles.size()).append(" | Unresolved: ").append(unresolved)
				.append(" | Metadata invisible: ").append(invisible).append("\n");
		sb.append("Workspace: ").append(quiescence.settled() ? "settled" : "STILL BUSY").append(" after ")
				.append(quiescence.rounds()).append(" round(s) | Unresolved type names: ")
				.append(types.entries().size()).append(types.truncated() ? " (truncated)" : "").append("\n");
		appendList(sb, "Unknown to PDE (neither workspace nor target platform)", unknown);
		appendList(sb, "Errors reading markers", problems.readErrors());

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("workspace", workspace);
		json.put("settled", quiescence.settled());
		json.put("settleRounds", quiescence.rounds());
		json.put("problems", problems.json());
		json.put("projects", projects);
		json.put("bundles", bundles);
		json.put("unknownBundles", unknown);
		json.put("unresolvedTypes", types.entries());
		json.put("unresolvedTypesTruncated", types.truncated());
		appendJsonBlock(sb, json);
		return new ToolResult(true, sb.toString());
	}

	private record UnresolvedTypes(List<Map<String, Object>> entries, boolean truncated) {
	}

	/**
	 * Every distinct name an ERROR marker says it cannot resolve, located once each.
	 * Deduplicating by name is what makes this affordable: a broken workspace produces
	 * hundreds of markers for a couple of dozen names.
	 */
	private static UnresolvedTypes unresolvedTypes(List<Map<String, Object>> markers, Path root) {
		Map<String, Set<String>> requestedBy = new LinkedHashMap<>();
		for (Map<String, Object> marker : markers) {
			if (!"error".equals(marker.get("severity"))) {
				continue;
			}
			Object kind = marker.get("problemKind");
			Object name = marker.get("unresolvedName");
			if (name == null || !(kind instanceof String k) || !UNRESOLVED_REFERENCE_KINDS.contains(k)) {
				continue;
			}
			requestedBy.computeIfAbsent(String.valueOf(name), n -> new TreeSet<>())
					.add(String.valueOf(marker.get("project")));
		}
		boolean truncated = requestedBy.size() > UNRESOLVED_TYPES_CAP;
		List<String> names = new ArrayList<>(requestedBy.keySet());
		if (truncated) {
			names = names.subList(0, UNRESOLVED_TYPES_CAP);
		}
		return new UnresolvedTypes(TypeLocator.locate(names, requestedBy, root), truncated);
	}

	/**
	 * The same lookup on its own — the reflex the doctor otherwise performs with a shell
	 * {@code find}, which is precisely the kind of out-of-tool verification that hides a
	 * missing fact.
	 */
	public static ToolResult locateType(List<String> names) {
		if (names == null || names.isEmpty()) {
			return new ToolResult(false, "Missing required argument: names");
		}
		List<Map<String, Object>> entries = TypeLocator.locate(names, Map.of(), resolveRoot(null));
		List<String> lines = new ArrayList<>();
		for (Map<String, Object> entry : entries) {
			StringBuilder line = new StringBuilder();
			line.append(entry.get("name")).append(" — ").append(entry.get("kind"));
			Object jdt = entry.get("jdtProjects");
			if (jdt instanceof List<?> list && !list.isEmpty()) {
				line.append(" — jdt: ").append(String.join(", ", asStringList(list)));
			}
			if (entry.get("diskPath") != null) {
				line.append(" — disk: ").append(entry.get("diskPath"));
				line.append(Boolean.TRUE.equals(entry.get("tracked")) ? " (tracked)"
						: Boolean.FALSE.equals(entry.get("tracked")) ? " (untracked)" : " (git: unknown)");
			}
			lines.add(line.toString());
		}
		StringBuilder sb = new StringBuilder();
		sb.append("# Locate type — ").append(entries.size()).append(" name(s)\n\n");
		appendCapped(sb, "Names", lines, 200);
		appendJsonBlock(sb, Map.of("types", entries));
		return new ToolResult(true, sb.toString());
	}

	/** The informative-only job shown while the doctor runs an external Maven build. */
	private record DoctorStatusJob(Job job, CountDownLatch done) {
	}

	private static final long DOCTOR_STATUS_AUTO_CLOSE_MS = 20 * 60 * 1000L;
	private static volatile DoctorStatusJob doctorStatusJob;

	/**
	 * A progress entry that says "the doctor is rebuilding with Maven, outside
	 * Eclipse". Deliberately holds NO scheduling rule: the work happens in another
	 * process, and a job with a rule would lock the workspace for minutes for
	 * nothing. It is not usefully cancellable either — cancelling would not kill the
	 * Maven process, which would make the button a lie.
	 *
	 * <p>The auto-close deadline is the point of the whole thing: the script brackets
	 * the build with start/end, and a script killed in between must not leak a job
	 * that sits in the Progress view forever. Same reasoning as the auto-build net.
	 */
	public static ToolResult doctorStatus(String phase, String detail) {
		String action = phase == null ? "" : phase.trim().toLowerCase();
		if (!"start".equals(action) && !"end".equals(action)) {
			return new ToolResult(false, "'phase' must be 'start' or 'end'.");
		}
		stopDoctorStatus();
		if ("end".equals(action)) {
			return new ToolResult(true, "# Doctor status — closed\n");
		}

		String label = "BlueMind Doctor — rebuild Maven (hors Eclipse)"
				+ (detail == null || detail.isBlank() ? "" : " : " + detail.trim());
		CountDownLatch done = new CountDownLatch(1);
		Job job = Job.create(label, monitor -> {
			SubMonitor sub = SubMonitor.convert(monitor, IProgressMonitor.UNKNOWN);
			long start = System.currentTimeMillis();
			while (!monitor.isCanceled()) {
				long elapsed = System.currentTimeMillis() - start;
				if (elapsed >= DOCTOR_STATUS_AUTO_CLOSE_MS) {
					break;
				}
				sub.subTask(elapsed / 1000 + "s — le workspace n'est pas verrouillé");
				try {
					if (done.await(1, TimeUnit.SECONDS)) {
						break;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			return Status.OK_STATUS;
		});
		job.setUser(false);
		doctorStatusJob = new DoctorStatusJob(job, done);
		// Scheduled from the UI thread like any other workbench-facing feedback, and
		// asynchronously so a busy UI thread can never stall the MCP response.
		if (PlatformUI.isWorkbenchRunning()) {
			Display.getDefault().asyncExec(job::schedule);
		} else {
			job.schedule();
		}
		return new ToolResult(true, "# Doctor status — shown\n\n" + label + "\nAuto-closes after "
				+ DOCTOR_STATUS_AUTO_CLOSE_MS / 60000 + " min.\n");
	}

	/** Closes the informative job, if any. Also called from the Activator's stop(). */
	public static void stopDoctorStatus() {
		DoctorStatusJob current = doctorStatusJob;
		doctorStatusJob = null;
		if (current == null) {
			return;
		}
		current.done().countDown();
		current.job().cancel();
	}

	public static ToolResult syncWorkingSets(String rootPath, boolean apply, boolean reset, String layout) {
		Path root = resolveRoot(rootPath);
		if (root == null) {
			return new ToolResult(false,
					"No 'path' given and the BlueMind global POM was not found to derive the repo root.");
		}
		if (!Files.isDirectory(root)) {
			return new ToolResult(false, "Root path is not a directory: " + root);
		}
		String layoutId = (layout == null || layout.isBlank()) ? WS_DEFAULT_LAYOUT : layout.toLowerCase();
		if (!Set.of(WS_LAYOUT_FLAT2, WS_LAYOUT_FLAT3, WS_LAYOUT_HYBRID).contains(layoutId)) {
			return new ToolResult(false,
					"Unknown layout '" + layout + "' — expected flat2, flat3 or hybrid.");
		}

		IWorkspace ws = ResourcesPlugin.getWorkspace();
		Map<String, List<IProject>> grouped = new TreeMap<>();
		for (IProject p : ws.getRoot().getProjects()) {
			String name = workingSetNameFor(root, p, layoutId);
			if (name != null) {
				grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(p);
			}
		}
		final Map<String, List<IProject>> desired = WS_LAYOUT_HYBRID.equals(layoutId)
				? foldHybridMisc(grouped)
				: grouped;

		Set<String> managedBefore = managedWorkingSetNames();
		List<IWorkingSet> allSets = allWorkingSets();
		Map<String, IWorkingSet> byName = new HashMap<>();
		for (IWorkingSet s : allSets) {
			byName.put(s.getName(), s);
		}

		List<String> toCreate = new ArrayList<>();
		List<String> toUpdate = new ArrayList<>();
		List<String> toRemove = new ArrayList<>();
		List<String> conflicts = new ArrayList<>();
		List<String> toDeleteOnReset = new ArrayList<>();

		if (reset) {
			for (IWorkingSet s : allSets) {
				toDeleteOnReset.add(s.getName());
			}
			toCreate.addAll(desired.keySet());
		} else {
			for (String name : desired.keySet()) {
				IWorkingSet existing = byName.get(name);
				if (existing == null) {
					toCreate.add(name);
				} else if (managedBefore.contains(name)) {
					if (!sameElements(existing, desired.get(name))) {
						toUpdate.add(name);
					}
				} else {
					conflicts.add(name);
				}
			}
			for (String name : managedBefore) {
				if (!desired.containsKey(name)) {
					toRemove.add(name);
				}
			}
		}

		boolean hasChanges = !toCreate.isEmpty() || !toUpdate.isEmpty() || !toRemove.isEmpty()
				|| !toDeleteOnReset.isEmpty();
		List<String> created = new ArrayList<>();
		List<String> updated = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		if (apply && hasChanges) {
			String message = reset
					? "Claude Code wants to reset ALL working sets in the workspace (including hand-made ones)"
							+ " and recreate them from the repo layout under " + root
							+ ". Projects themselves are not affected. Allow?"
					: "Claude Code wants to sync working sets under " + root + ": create " + toCreate.size()
							+ ", update " + toUpdate.size() + ", remove " + toRemove.size()
							+ " (hand-made working sets are never touched). Allow?";
			WorkspaceConsent.Decision consent = WorkspaceConsent.checkWorkingSets(message);
			if (!consent.allowed()) {
				return new ToolResult(false, "Sync refused: " + consent.reason());
			}

			Display.getDefault().syncExec(() -> {
				IWorkingSetManager manager = PlatformUI.getWorkbench().getWorkingSetManager();
				if (reset) {
					for (IWorkingSet s : manager.getAllWorkingSets()) {
						manager.removeWorkingSet(s);
					}
					for (String name : toCreate) {
						createManagedSet(manager, name, desired.get(name), created, errors);
					}
				} else {
					for (String name : toRemove) {
						IWorkingSet s = manager.getWorkingSet(name);
						if (s != null) {
							manager.removeWorkingSet(s);
							removed.add(name);
						}
					}
					for (String name : toUpdate) {
						IWorkingSet s = manager.getWorkingSet(name);
						if (s != null) {
							s.setElements(elementsFor(desired.get(name)));
							updated.add(name);
						}
					}
					for (String name : toCreate) {
						createManagedSet(manager, name, desired.get(name), created, errors);
					}
				}
			});

			Set<String> managedAfter = reset ? new TreeSet<>(created) : new TreeSet<>(managedBefore);
			if (!reset) {
				managedAfter.removeAll(toRemove);
				managedAfter.addAll(created);
			}
			Activator.getDefault().getPreferenceStore().setValue(Activator.PREF_WORKINGSETS_MANAGED,
					String.join("\n", managedAfter));
		}

		boolean ok = errors.isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("# Sync working sets").append(reset ? " — RESET" : "").append(" — ")
				.append(!apply ? "DRY-RUN" : ok ? "OK" : "ISSUES").append("\n\n");
		sb.append("Root: ").append(root).append("\n");
		sb.append("Layout: ").append(layoutId).append(" | Sets: ").append(desired.size()).append("\n");
		if (reset) {
			sb.append("Existing working sets to delete (ALL, including hand-made): ")
					.append(toDeleteOnReset.size()).append("\n");
			appendCapped(sb, "To delete", toDeleteOnReset, 200);
			appendCapped(sb, apply ? "Created" : "Would be created", apply ? created : toCreate, 200);
		} else if (!apply) {
			appendCapped(sb, "Would be created", toCreate, 100);
			appendCapped(sb, "Would be updated", toUpdate, 100);
			appendCapped(sb, "Would be removed — now empty", toRemove, 100);
			appendCapped(sb, "Skipped — hand-made set with this name", conflicts, 100);
		} else {
			appendCapped(sb, "Created", created, 100);
			appendCapped(sb, "Updated", updated, 100);
			appendCapped(sb, "Removed — now empty", removed, 100);
			appendCapped(sb, "Skipped — hand-made set with this name", conflicts, 100);
		}
		appendList(sb, "Errors", errors);

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("root", root.toString());
		json.put("layout", layoutId);
		json.put("apply", apply);
		json.put("reset", reset);
		json.put("toCreate", toCreate);
		json.put("toUpdate", toUpdate);
		json.put("toRemove", reset ? toDeleteOnReset : toRemove);
		json.put("conflicts", conflicts);
		appendJsonBlock(sb, json);
		return new ToolResult(ok, sb.toString());
	}

	private static String workingSetNameFor(Path root, IProject project, String layout) {
		IPath location = project.getLocation();
		if (location == null) {
			return null;
		}
		Path parent = Path.of(location.toOSString()).getParent();
		if (parent == null || !parent.startsWith(root)) {
			return null;
		}
		String rel = root.relativize(parent).toString().replace('\\', '/');
		if (WS_LAYOUT_FLAT2.equals(layout)) {
			return pathPrefix(rel, 2);
		}
		if (WS_LAYOUT_HYBRID.equals(layout)) {
			boolean underDeepBranch = rel.equals(WS_HYBRID_DEEP_BRANCH)
					|| rel.startsWith(WS_HYBRID_DEEP_BRANCH + "/");
			return pathPrefix(rel, underDeepBranch ? 3 : 2);
		}
		return pathPrefix(rel, 3); // flat3
	}

	/** First {@code depth} path segments of a '/'-separated relative path. */
	private static String pathPrefix(String rel, int depth) {
		int cut = -1;
		int seen = 0;
		for (int i = 0; i < rel.length(); i++) {
			if (rel.charAt(i) == '/' && ++seen == depth) {
				cut = i;
				break;
			}
		}
		return cut < 0 ? rel : rel.substring(0, cut);
	}

	/** Hybrid layout: fold open/parent/* sets below the floor into open/parent/~misc. */
	private static Map<String, List<IProject>> foldHybridMisc(Map<String, List<IProject>> desired) {
		String prefix = WS_HYBRID_DEEP_BRANCH + "/";
		String miscName = WS_HYBRID_DEEP_BRANCH + "/~misc";
		Map<String, List<IProject>> folded = new TreeMap<>();
		for (Map.Entry<String, List<IProject>> e : desired.entrySet()) {
			String name = e.getKey();
			if (name.startsWith(prefix) && e.getValue().size() < WS_MISC_FLOOR) {
				name = miscName;
			}
			folded.computeIfAbsent(name, k -> new ArrayList<>()).addAll(e.getValue());
		}
		return folded;
	}

	private static void createManagedSet(IWorkingSetManager manager, String name, List<IProject> projects,
			List<String> created, List<String> errors) {
		try {
			IWorkingSet workingSet = manager.createWorkingSet(name, elementsFor(projects));
			// setId must be called before addWorkingSet, otherwise the set silently
			// doesn't show up as a Java working set in the Package Explorer.
			workingSet.setId(JAVA_WORKING_SET_ID);
			manager.addWorkingSet(workingSet);
			created.add(name);
		} catch (RuntimeException e) {
			errors.add(name + ": create failed — " + e.getMessage());
		}
	}

	private static IAdaptable[] elementsFor(List<IProject> projects) {
		IAdaptable[] elements = new IAdaptable[projects.size()];
		for (int i = 0; i < projects.size(); i++) {
			elements[i] = elementFor(projects.get(i));
		}
		return elements;
	}

	private static IAdaptable elementFor(IProject project) {
		if (project.isOpen()) {
			try {
				if (project.hasNature(JavaCore.NATURE_ID)) {
					return JavaCore.create(project);
				}
			} catch (CoreException ignored) {
				// fall through to the plain IProject element
			}
		}
		return project;
	}

	private static boolean sameElements(IWorkingSet existing, List<IProject> desiredProjects) {
		Set<String> existingNames = new HashSet<>();
		for (IAdaptable e : existing.getElements()) {
			IProject p = Platform.getAdapterManager().getAdapter(e, IProject.class);
			if (p == null && e instanceof IJavaProject jp) {
				p = jp.getProject();
			}
			if (p != null) {
				existingNames.add(p.getName());
			}
		}
		Set<String> desiredNames = new HashSet<>();
		for (IProject p : desiredProjects) {
			desiredNames.add(p.getName());
		}
		return existingNames.equals(desiredNames);
	}

	private static Set<String> managedWorkingSetNames() {
		String raw = Activator.getDefault().getPreferenceStore().getString(Activator.PREF_WORKINGSETS_MANAGED);
		Set<String> names = new TreeSet<>();
		if (raw == null || raw.isBlank()) {
			return names;
		}
		for (String line : raw.split("\n")) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				names.add(trimmed);
			}
		}
		return names;
	}

	private static List<IWorkingSet> allWorkingSets() {
		List<IWorkingSet> result = new ArrayList<>();
		Display.getDefault().syncExec(() -> {
			if (!PlatformUI.isWorkbenchRunning()) {
				return;
			}
			result.addAll(List.of(PlatformUI.getWorkbench().getWorkingSetManager().getAllWorkingSets()));
		});
		return result;
	}

	private static Map<String, Path> scanDiskProjects(Path root) throws IOException {
		Map<String, Path> found = new LinkedHashMap<>();
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
				if (SKIP_DIRS.contains(dirName)) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				Path dotProject = dir.resolve(".project");
				if (Files.isRegularFile(dotProject)) {
					try {
						IProjectDescription desc = ws
								.loadProjectDescription(IPath.fromOSString(dotProject.toString()));
						found.put(desc.getName(), dotProject);
					} catch (CoreException ignored) {
						// malformed .project: skip, not our concern here
					}
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return found;
	}

	private static Map<String, List<String>> workingSetMembership() {
		Map<String, List<String>> membership = new LinkedHashMap<>();
		Display.getDefault().syncExec(() -> {
			if (!PlatformUI.isWorkbenchRunning()) {
				return;
			}
			for (IWorkingSet workingSet : PlatformUI.getWorkbench().getWorkingSetManager().getAllWorkingSets()) {
				for (IAdaptable element : workingSet.getElements()) {
					IProject p = Platform.getAdapterManager().getAdapter(element, IProject.class);
					if (p == null && element instanceof IJavaProject jp) {
						p = jp.getProject();
					}
					if (p != null) {
						membership.computeIfAbsent(p.getName(), k -> new ArrayList<>()).add(workingSet.getName());
					}
				}
			}
		});
		return membership;
	}

	private static String relativize(Path root, Path location) {
		if (root != null && location.startsWith(root)) {
			return root.relativize(location).toString();
		}
		return location.toString();
	}

	private static void appendJsonBlock(StringBuilder sb, Object data) {
		sb.append("\n```json\n").append(McpJson.write(data)).append("\n```\n");
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

	/**
	 * Refresh families first, build families after: a {@code git pull} or a workspace
	 * change starts refresh jobs, and the build they will trigger does not exist yet.
	 */
	private static final Object[] SETTLE_FAMILIES = { ResourcesPlugin.FAMILY_MANUAL_REFRESH,
			ResourcesPlugin.FAMILY_AUTO_REFRESH, ResourcesPlugin.FAMILY_MANUAL_BUILD,
			ResourcesPlugin.FAMILY_AUTO_BUILD };

	private static final long SETTLE_DEADLINE_MS = 120_000L;
	private static final int SETTLE_MAX_ROUNDS = 12;

	/** Whether the workspace went quiet, and how many rounds it took to get there. */
	record Quiescence(boolean settled, int rounds) {
	}

	/**
	 * Wait until nothing is refreshing or building any more.
	 *
	 * <p>Joining each family once is not enough, and the reason is a race, not a
	 * slowness: the build a refresh triggers is not scheduled yet when the join runs, so
	 * the join returns immediately and the caller reads markers from a workspace that is
	 * about to change under it. Hence the loop — join all four families, check that none
	 * has anything pending, start over otherwise — bounded by a deadline AND a round
	 * count, because a workspace that never settles must not hang the MCP call.
	 *
	 * <p>The JDT indexer has no job family at all; the only public way to wait for it is
	 * a search with {@code WAIT_UNTIL_READY_TO_SEARCH}, which is why the settle ends
	 * with one. Without it a {@code disk-only} verdict could be a race with the index.
	 */
	static Quiescence settle() {
		long deadline = System.currentTimeMillis() + SETTLE_DEADLINE_MS;
		int rounds = 0;
		boolean quiet = false;
		while (rounds < SETTLE_MAX_ROUNDS) {
			rounds++;
			for (Object family : SETTLE_FAMILIES) {
				try {
					Job.getJobManager().join(family, new NullProgressMonitor());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return new Quiescence(false, rounds);
				} catch (OperationCanceledException ignored) {
					// best-effort wait
				}
			}
			if (activeFamilies().isEmpty()) {
				quiet = true;
				break;
			}
			if (System.currentTimeMillis() > deadline) {
				break;
			}
		}
		TypeLocator.waitForIndexer();
		return new Quiescence(quiet, rounds);
	}

	private static void waitForBuildJobs() {
		settle();
	}

	/** Names of the settle families that still have a running or waiting job. */
	private static List<String> activeFamilies() {
		List<String> active = new ArrayList<>();
		for (Object family : SETTLE_FAMILIES) {
			for (Job job : Job.getJobManager().find(family)) {
				int state = job.getState();
				if (state == Job.RUNNING || state == Job.WAITING) {
					active.add(familyName(family));
					break;
				}
			}
		}
		return active;
	}

	private static String familyName(Object family) {
		if (family == ResourcesPlugin.FAMILY_MANUAL_REFRESH) {
			return "manual-refresh";
		}
		if (family == ResourcesPlugin.FAMILY_AUTO_REFRESH) {
			return "auto-refresh";
		}
		if (family == ResourcesPlugin.FAMILY_MANUAL_BUILD) {
			return "manual-build";
		}
		return "auto-build";
	}
}
