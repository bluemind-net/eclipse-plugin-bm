package net.bluemind.devtools.testrunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class PomPropertyReader {

	private static final ILog LOG = Platform.getLog(PomPropertyReader.class);
	private static volatile Path cachedPomPath;

	public record PomProperties(String dockerDevenvTag, String targetPlatformVersion,
			String targetRepoUrl, String resolvedTestArgLine, String requiredJavaVersion) {

		public boolean isFileTarget() {
			return targetRepoUrl != null && targetRepoUrl.startsWith("file:");
		}
	}

	/**
	 * Locates the global POM by scanning workspace project locations and walking up
	 * the directory tree to find {@code global/pom.xml}.
	 */
	public static Optional<Path> findGlobalPom() {
		Path cached = cachedPomPath;
		if (cached != null && Files.exists(cached)) {
			return Optional.of(cached);
		}

		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isOpen()) {
				continue;
			}
			var location = project.getLocation();
			if (location == null) {
				continue;
			}

			Optional<Path> found = walkUpForGlobalPom(Path.of(location.toOSString()));
			if (found.isPresent()) {
				cachedPomPath = found.get();
				return found;
			}
		}

		LOG.warn("BlueMind global POM not found in any workspace project ancestry");
		return Optional.empty();
	}

	/**
	 * Derives the BlueMind repo root ({@code <root>/global/pom.xml} walked up
	 * three levels) from {@link #findGlobalPom()}.
	 */
	public static Optional<Path> findRepoRoot() {
		return findGlobalPom().map(pom -> pom.getParent().getParent().getParent());
	}

	/**
	 * Fallback for a brand-new workspace with no project imported yet: walks up
	 * from the Eclipse workspace location itself. Only useful when the workspace
	 * (Eclipse {@code -data}) is created inside the BlueMind repo worktree.
	 */
	public static Optional<Path> findRepoRootFromWorkspaceLocation() {
		var location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
		if (location == null) {
			return Optional.empty();
		}
		Optional<Path> found = walkUpForGlobalPom(Path.of(location.toOSString()));
		found.ifPresent(pom -> cachedPomPath = pom);
		return found.map(pom -> pom.getParent().getParent().getParent());
	}

	/**
	 * Walks up from {@code start} looking for {@code global/pom.xml} as a direct
	 * child (matches a starting point already inside {@code open/}, e.g. a
	 * project location) or as a child of {@code open/} (matches a starting point
	 * at or above the repo root, e.g. the Eclipse workspace location itself when
	 * it's created next to — rather than inside — {@code open/}).
	 */
	private static Optional<Path> walkUpForGlobalPom(Path start) {
		Path current = start;
		while (current != null) {
			for (Path candidate : new Path[] { current.resolve("global/pom.xml"),
					current.resolve("open/global/pom.xml") }) {
				if (Files.exists(candidate)) {
					LOG.info("Found BlueMind global POM: " + candidate);
					return Optional.of(candidate);
				}
			}
			current = current.getParent();
		}
		return Optional.empty();
	}

	/**
	 * Parses the POM XML and extracts the three key properties, resolving
	 * {@code ${docker.devenv.tag}} within {@code tycho.testArgLine}.
	 */
	public static Optional<PomProperties> readProperties(Path pomPath) {
		try {
			var dbf = DocumentBuilderFactory.newInstance();
			Document doc = dbf.newDocumentBuilder().parse(pomPath.toFile());

			NodeList propsNodes = doc.getElementsByTagName("properties");
			if (propsNodes.getLength() == 0) {
				LOG.warn("No <properties> element found in POM: " + pomPath);
				return Optional.empty();
			}

			Element propsEl = (Element) propsNodes.item(0);
			String dockerTag = getChildText(propsEl, "docker.devenv.tag");
			String targetVersion = getChildText(propsEl, "target-platform-version");
			String testArgLine = getChildText(propsEl, "tycho.testArgLine");

			if (dockerTag == null || testArgLine == null) {
				LOG.warn("Missing required properties in POM: docker.devenv.tag=" + dockerTag
						+ ", tycho.testArgLine=" + (testArgLine != null ? "present" : "null"));
				return Optional.empty();
			}

			String targetRepoUrl = findBluemindDepsRepoUrl(doc);
			if (targetRepoUrl != null && targetVersion != null) {
				targetRepoUrl = targetRepoUrl.replace("${target-platform-version}", targetVersion);
			}

			String resolved = testArgLine.replace("${docker.devenv.tag}", dockerTag);
			String localOpts = readLocalJvmOptions();
			if (!localOpts.isEmpty()) {
				resolved = resolved + " " + localOpts;
			}
			String requiredJavaVersion = findCompilerTargetVersion(doc);
			return Optional.of(new PomProperties(dockerTag, targetVersion, targetRepoUrl, resolved, requiredJavaVersion));
		} catch (Exception e) {
			LOG.error("Failed to parse POM: " + pomPath, e);
			return Optional.empty();
		}
	}

	private static String findBluemindDepsRepoUrl(Document doc) {
		NodeList repos = doc.getElementsByTagName("repository");
		for (int i = 0; i < repos.getLength(); i++) {
			if (!(repos.item(i) instanceof Element repo)) {
				continue;
			}
			String id = getChildText(repo, "id");
			String layout = getChildText(repo, "layout");
			if ("bluemind-deps".equals(id) && "p2".equals(layout)) {
				return getChildText(repo, "url");
			}
		}
		return null;
	}

	/**
	 * Finds the {@code <target>} (falling back to {@code <source>}) release version
	 * configured on the {@code maven-compiler-plugin}, wherever it's declared
	 * ({@code build}, {@code pluginManagement}, or a profile).
	 */
	private static String findCompilerTargetVersion(Document doc) {
		NodeList plugins = doc.getElementsByTagName("plugin");
		for (int i = 0; i < plugins.getLength(); i++) {
			if (!(plugins.item(i) instanceof Element plugin)) {
				continue;
			}
			if (!"maven-compiler-plugin".equals(getChildText(plugin, "artifactId"))) {
				continue;
			}
			NodeList configs = plugin.getElementsByTagName("configuration");
			for (int c = 0; c < configs.getLength(); c++) {
				if (!(configs.item(c) instanceof Element config)) {
					continue;
				}
				String target = getChildText(config, "target");
				if (target != null) {
					return target;
				}
				String source = getChildText(config, "source");
				if (source != null) {
					return source;
				}
			}
		}
		return null;
	}

	private static String getChildText(Element parent, String tagName) {
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child instanceof Element el && el.getTagName().equals(tagName)) {
				return el.getTextContent().trim();
			}
		}
		return null;
	}

	/**
	 * Reads extra JVM options from {@code ~/.config/bluemind/jvm.options} if it
	 * exists. One option per line, lines starting with {@code #} are comments.
	 */
	static String readLocalJvmOptions() {
		Path jvmOptions = Path.of(System.getProperty("user.home"), ".config", "bluemind", "jvm.options");
		if (!Files.exists(jvmOptions)) {
			return "";
		}
		try {
			return Files.readAllLines(jvmOptions).stream()
					.map(String::trim)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.collect(Collectors.joining(" "));
		} catch (IOException e) {
			LOG.warn("Failed to read " + jvmOptions + ": " + e.getMessage());
			return "";
		}
	}

	static void clearCache() {
		cachedPomPath = null;
	}
}
