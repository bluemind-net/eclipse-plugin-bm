package net.bluemind.devtools.testrunner.mcp;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;

/**
 * Where does a type actually live? Answers the one question the doctor used to answer
 * with a shell {@code find}, and it answers it with a <em>fact</em> rather than a guess:
 *
 * <ul>
 * <li>{@code jdt-visible} — the JDT model knows the type. The resource tree is fine and
 * only the build state is stale, so the remedy is a clean of the dependent.</li>
 * <li>{@code disk-only} — no JDT hit, yet {@code <Name>.java} sits in a source folder of
 * an open project. Eclipse's resource tree (or its index) is out of sync with the disk:
 * the supplier needs a refresh, not a rebuild.</li>
 * <li>{@code closed-provider} — no JDT hit, no hit in an open project, yet
 * {@code <Name>.java} sits in a source folder of a <em>closed</em> project. The type
 * exists, it is simply not being looked at: the remedy is to open the supplier.</li>
 * <li>{@code nowhere} — none of the above. Neither JDT, nor the disk of an open project,
 * nor the disk of a closed one. A real code error, or a leftover of codegen whose input
 * has been deleted.</li>
 * </ul>
 *
 * <p>Two remedies that look alike are separated by this single distinction, which is why
 * it belongs here and not in a script: only the JDT model can say whether JDT sees a
 * type, and only the disk can say whether the file is there.
 */
final class TypeLocator {

	static final String KIND_JDT_VISIBLE = "jdt-visible";
	static final String KIND_DISK_ONLY = "disk-only";
	static final String KIND_CLOSED_PROVIDER = "closed-provider";
	static final String KIND_NOWHERE = "nowhere";

	private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", "bin", ".metadata",
			".settings");

	private TypeLocator() {
	}

	/**
	 * One JSON entry per distinct name, in the order the names were given.
	 *
	 * @param names       type names, simple or fully qualified
	 * @param requestedBy name -> projects whose markers ask for it; may be empty
	 * @param root        repo root, for relative (transmissible) disk paths
	 */
	static List<Map<String, Object>> locate(Collection<String> names, Map<String, Set<String>> requestedBy,
			Path root) {
		List<String> wanted = new ArrayList<>(new LinkedHashSet<>(names));
		if (wanted.isEmpty()) {
			return List.of();
		}
		Set<String> simpleNames = new HashSet<>();
		for (String name : wanted) {
			simpleNames.add(simpleName(name));
		}
		Map<String, List<DiskHit>> diskHits = scanSourceFolders(simpleNames, true);
		Set<String> unmatched = new HashSet<>(simpleNames);
		unmatched.removeAll(diskHits.keySet());
		Map<String, List<DiskHit>> closedHits = unmatched.isEmpty() ? Map.of() : scanSourceFolders(unmatched, false);

		List<Map<String, Object>> out = new ArrayList<>(wanted.size());
		try (GitIndexReader git = new GitIndexReader()) {
			for (String name : wanted) {
				String simple = simpleName(name);
				out.add(entry(name, diskHits.getOrDefault(simple, List.of()),
						closedHits.getOrDefault(simple, List.of()), requestedBy.getOrDefault(name, Set.of()), root,
						git));
			}
		}
		return out;
	}

	private static Map<String, Object> entry(String name, List<DiskHit> hits, List<DiskHit> closedHits,
			Set<String> requesters, Path root, GitIndexReader git) {
		List<String> jdtProjects = jdtProjectsFor(name);
		boolean jdtKnows = jdtProjects != null;
		List<DiskHit> effective = hits.isEmpty() ? closedHits : hits;
		Set<String> diskProjects = new TreeSet<>();
		for (DiskHit hit : effective) {
			diskProjects.add(hit.project().getName());
		}
		DiskHit first = effective.isEmpty() ? null : effective.get(0);

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("name", name);
		json.put("kind", jdtKnows ? KIND_JDT_VISIBLE
				: !hits.isEmpty() ? KIND_DISK_ONLY : !closedHits.isEmpty() ? KIND_CLOSED_PROVIDER : KIND_NOWHERE);
		json.put("jdtProjects", jdtKnows ? jdtProjects : List.of());
		json.put("diskProjects", new ArrayList<>(diskProjects));
		json.put("diskPath", first == null ? null : relativize(root, first.file()));
		json.put("tracked", first == null ? null : git.isTracked(first.project(), first.file()));
		json.put("requestedBy", new ArrayList<>(new TreeSet<>(requesters)));
		return json;
	}

	/**
	 * Workspace projects in which JDT can find the type, or {@code null} when JDT does
	 * not know it at all. An empty list is <em>not</em> the same answer: it means JDT
	 * found the type outside the workspace (a target-platform jar), which still makes it
	 * a build-state problem rather than a missing source.
	 */
	private static List<String> jdtProjectsFor(String name) {
		String simple = simpleName(name);
		String pkg = packageName(name);
		Set<String> projects = new TreeSet<>();
		boolean[] found = { false };
		TypeNameRequestor requestor = new TypeNameRequestor() {
			@Override
			public void acceptType(int modifiers, char[] packageName, char[] simpleTypeName,
					char[][] enclosingTypeNames, String path) {
				found[0] = true;
				String project = projectOfPath(path);
				if (project != null) {
					projects.add(project);
				}
			}
		};
		int rule = SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE;
		try {
			// WAIT_UNTIL_READY_TO_SEARCH is also the only public way to wait for the JDT
			// indexer: without it a 'disk-only' verdict could just be a race with the index.
			new SearchEngine().searchAllTypeNames(pkg == null ? null : pkg.toCharArray(), rule,
					simple.toCharArray(), rule, IJavaSearchConstants.TYPE, SearchEngine.createWorkspaceScope(),
					requestor, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, new NullProgressMonitor());
		} catch (JavaModelException | RuntimeException e) {
			// A failed search must not claim the type is missing: report "not found by
			// JDT" only when the search actually ran.
			return null;
		}
		return found[0] ? new ArrayList<>(projects) : null;
	}

	/** Blocks until the JDT indexer is ready, with a search that matches nothing. */
	static void waitForIndexer() {
		try {
			new SearchEngine().searchAllTypeNames(null, SearchPattern.R_EXACT_MATCH,
					"zzNoSuchTypezz$$".toCharArray(), SearchPattern.R_EXACT_MATCH, IJavaSearchConstants.TYPE,
					SearchEngine.createWorkspaceScope(), new TypeNameRequestor() {
					}, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, new NullProgressMonitor());
		} catch (JavaModelException | RuntimeException ignored) {
			// best-effort sync point
		}
	}

	private static String projectOfPath(String path) {
		if (path == null || !path.startsWith("/")) {
			// A binary type in a jar comes back as an absolute filesystem path.
			return null;
		}
		int end = path.indexOf('/', 1);
		String name = end < 0 ? path.substring(1) : path.substring(1, end);
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		return project.exists() ? name : null;
	}

	private record DiskHit(IProject project, Path file) {
	}

	/**
	 * One walk of every source folder of every project open (or closed) as asked,
	 * matching file names against the whole wanted set at once. Deliberately on disk and
	 * not through the resource tree: the resource tree being out of date is the very
	 * thing under diagnosis.
	 */
	private static Map<String, List<DiskHit>> scanSourceFolders(Set<String> simpleNames, boolean wantOpen) {
		Map<String, List<DiskHit>> hits = new HashMap<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isOpen() != wantOpen) {
				continue;
			}
			IPath location = project.getLocation();
			if (location == null) {
				continue;
			}
			Path projectDir = Path.of(location.toOSString());
			for (Path folder : sourceFolders(project, projectDir)) {
				collect(project, folder, simpleNames, hits);
			}
		}
		return hits;
	}

	/**
	 * Declared source folders on disk, or the project directory itself when the
	 * classpath cannot be read — a project whose {@code .classpath} Eclipse never saw is
	 * exactly the kind this lookup exists for, so it must not fall out of the scan.
	 */
	private static List<Path> sourceFolders(IProject project, Path projectDir) {
		List<Path> folders = new ArrayList<>();
		IJavaProject java = JavaCore.create(project);
		if (java != null && java.exists()) {
			try {
				for (IClasspathEntry entry : java.getRawClasspath()) {
					if (entry.getEntryKind() != IClasspathEntry.CPE_SOURCE) {
						continue;
					}
					IPath path = entry.getPath();
					if (path.segmentCount() < 2) {
						continue;
					}
					Path dir = projectDir.resolve(path.removeFirstSegments(1).toOSString());
					if (Files.isDirectory(dir)) {
						folders.add(dir);
					}
				}
			} catch (JavaModelException ignored) {
				// fall through to the whole project directory
			}
		}
		return folders.isEmpty() ? List.of(projectDir) : folders;
	}

	private static void collect(IProject project, Path folder, Set<String> simpleNames,
			Map<String, List<DiskHit>> hits) {
		try {
			Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					Path name = dir.getFileName();
					return name != null && SKIP_DIRS.contains(name.toString()) ? FileVisitResult.SKIP_SUBTREE
							: FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					String fileName = file.getFileName().toString();
					if (!fileName.endsWith(".java")) {
						return FileVisitResult.CONTINUE;
					}
					String base = fileName.substring(0, fileName.length() - ".java".length());
					if (simpleNames.contains(base)) {
						hits.computeIfAbsent(base, k -> new ArrayList<>()).add(new DiskHit(project, file));
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
			// an unreadable source folder is not a verdict about the type
		}
	}

	static String simpleName(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(dot + 1);
	}

	private static String packageName(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0) {
			return null;
		}
		String pkg = name.substring(0, dot);
		// 'Outer.Inner' is not a package: only a lower-case first segment is one.
		return pkg.isEmpty() || Character.isUpperCase(pkg.charAt(0)) ? null : pkg;
	}

	private static String relativize(Path root, Path file) {
		if (root != null && file.startsWith(root)) {
			return root.relativize(file).toString();
		}
		return file.getFileName().toString();
	}
}
