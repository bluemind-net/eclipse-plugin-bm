package net.bluemind.devtools.testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.osgi.framework.Bundle;

import net.bluemind.devtools.Activator;

/**
 * Whether PDE's target platform actually resolved and, when it did not, a
 * diagnosis of the one cause PDE's own error never names: a local bundle pool
 * whose {@code artifacts.xml} still lists a folder-shaped bundle that is gone
 * from disk. PDE hands p2 the bundle pools of every recent workspace as
 * artifact sources and p2 prefers local copies, so a single stale entry in an
 * unrelated workspace's pool fails the Collect phase with a bare
 * NullPointerException (p2 zips the folder, {@code File.listFiles()} returns
 * null) instead of falling back to the remote repository.
 */
public final class TargetPlatformHealth {

	private static final ILog LOG = Platform.getLog(TargetPlatformHealth.class);

	private static final String PDE_CORE_BUNDLE = "org.eclipse.pde.core";
	/** Where PDE's private p2 agent persists the artifact repositories it knows. */
	private static final String PDE_AGENT_ARTIFACT_REPOS = ".p2/org.eclipse.equinox.p2.engine/.settings/"
			+ "org.eclipse.equinox.p2.artifact.repository.prefs";
	private static final String WORKSPACE_BUNDLE_POOL = ".metadata/.plugins/org.eclipse.pde.core/.bundle_pool";
	private static final String IDE_PLUGIN_ID = "org.eclipse.ui.ide";
	private static final String RECENT_WORKSPACES_KEY = "RECENT_WORKSPACES";

	private static final String ARTIFACT_FOLDER = "artifact.folder";
	private static final String ARTIFACT_REFERENCE = "artifact.reference";
	private static final String REPO_URL_PREFIX = "${repoUrl}/";
	private static final Pattern RULE_CLASSIFIER = Pattern.compile("\\(classifier=([^)]+)\\)");

	private static final int MAX_STATUS_CHILDREN = 10;

	public record StalePoolEntry(Path repositoryFile, String id, String version, Path missingFolder) {
	}

	private TargetPlatformHealth() {
	}

	/**
	 * Empty when the workspace target resolved without error, when there is no
	 * workspace target, or when PDE has not resolved it yet: only a failed
	 * resolution is reported.
	 */
	public static Optional<String> describeWorkspaceTargetProblem() {
		Activator activator = Activator.getDefault();
		if (activator == null) {
			return Optional.empty();
		}
		var ctx = activator.getBundle().getBundleContext();
		var ref = ctx.getServiceReference(ITargetPlatformService.class);
		if (ref == null) {
			return Optional.empty();
		}
		ITargetPlatformService service = ctx.getService(ref);
		try {
			// getWorkspaceTargetDefinition() would create PDE's default target when none is set.
			if (service.getWorkspaceTargetHandle() == null) {
				return Optional.empty();
			}
			return describeProblem(service.getWorkspaceTargetDefinition());
		} catch (CoreException e) {
			LOG.warn("Could not read the workspace target platform: " + e.getMessage());
			return Optional.empty();
		} finally {
			ctx.ungetService(ref);
		}
	}

	public static Optional<String> describeProblem(ITargetDefinition target) {
		if (target == null || !target.isResolved()) {
			return Optional.empty();
		}
		IStatus status = target.getStatus();
		if (status == null || !status.matches(IStatus.ERROR)) {
			return Optional.empty();
		}
		StringBuilder sb = new StringBuilder();
		appendStatus(sb, status, 0);
		List<StalePoolEntry> stale = findStalePoolEntries();
		if (!stale.isEmpty()) {
			sb.append("\nLikely cause: these local bundle pools list folder-shaped bundles that are gone from disk."
					+ " p2 picks them over the remote repository and fails (typically with a"
					+ " NullPointerException during the Collect phase):\n");
			for (StalePoolEntry entry : stale) {
				sb.append("  - ").append(entry.id()).append(' ').append(entry.version()).append(" in ")
						.append(entry.repositoryFile()).append(" (missing ").append(entry.missingFolder())
						.append(")\n");
			}
			sb.append("Fix: remove those <artifact> entries from the listed file (or delete that whole bundle pool,"
					+ " it is only a cache), then reload the target platform.\n");
		}
		return Optional.of(sb.toString().strip());
	}

	public static List<StalePoolEntry> findStalePoolEntries() {
		List<StalePoolEntry> stale = new ArrayList<>();
		for (Path repository : localArtifactRepositories()) {
			stale.addAll(scanRepository(repository));
		}
		return stale;
	}

	private static void appendStatus(StringBuilder sb, IStatus status, int depth) {
		sb.append("  ".repeat(depth)).append(depth == 0 ? "" : "- ").append(status.getMessage()).append('\n');
		Set<String> seen = new HashSet<>();
		int shown = 0;
		int skipped = 0;
		for (IStatus child : status.getChildren()) {
			// PDE repeats the same failure once per location sharing the p2 profile.
			if (!child.matches(IStatus.ERROR) || !seen.add(child.getMessage())) {
				continue;
			}
			if (shown < MAX_STATUS_CHILDREN) {
				appendStatus(sb, child, depth + 1);
				shown++;
			} else {
				skipped++;
			}
		}
		if (skipped > 0) {
			sb.append("  ".repeat(depth + 1)).append("- ... and ").append(skipped).append(" more\n");
		}
	}

	/**
	 * The same local repositories PDE's resolution draws from: everything its p2
	 * agent remembers (current workspace pool, Eclipse install, pools of
	 * workspaces it saw before) plus the pool of every recent workspace.
	 */
	private static Set<Path> localArtifactRepositories() {
		Set<Path> repositories = new LinkedHashSet<>();
		Bundle pde = Platform.getBundle(PDE_CORE_BUNDLE);
		if (pde != null) {
			Path prefs = Platform.getStateLocation(pde).append(PDE_AGENT_ARTIFACT_REPOS).toPath();
			if (Files.isRegularFile(prefs)) {
				Properties known = new Properties();
				try (InputStream in = Files.newInputStream(prefs)) {
					known.load(in);
				} catch (IOException e) {
					LOG.warn("Could not read " + prefs + ": " + e.getMessage());
				}
				for (String key : known.stringPropertyNames()) {
					if (key.endsWith("/uri")) {
						toLocalPath(known.getProperty(key)).ifPresent(repositories::add);
					}
				}
			}
		}
		String recent = ConfigurationScope.INSTANCE.getNode(IDE_PLUGIN_ID).get(RECENT_WORKSPACES_KEY, "");
		for (String workspace : recent.split("\n")) {
			if (!workspace.isBlank()) {
				repositories.add(Path.of(workspace.strip(), WORKSPACE_BUNDLE_POOL).normalize());
			}
		}
		return repositories;
	}

	private static Optional<Path> toLocalPath(String uri) {
		try {
			URI parsed = URI.create(uri);
			if (!"file".equals(parsed.getScheme())) {
				return Optional.empty();
			}
			return Optional.of(Path.of(parsed).normalize());
		} catch (IllegalArgumentException e) {
			LOG.warn("Ignoring unparsable repository location " + uri + ": " + e.getMessage());
			return Optional.empty();
		}
	}

	/** p2 loads {@code artifacts.jar} before {@code artifacts.xml} when both exist. */
	private static List<StalePoolEntry> scanRepository(Path repository) {
		Path jar = repository.resolve("artifacts.jar");
		Path xml = repository.resolve("artifacts.xml");
		try {
			if (Files.isRegularFile(jar)) {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					ZipEntry entry = zip.getEntry("artifacts.xml");
					if (entry != null) {
						try (InputStream in = zip.getInputStream(entry)) {
							return scan(repository, jar, in);
						}
					}
				}
			} else if (Files.isRegularFile(xml)) {
				try (InputStream in = Files.newInputStream(xml)) {
					return scan(repository, xml, in);
				}
			}
		} catch (IOException | XMLStreamException e) {
			LOG.warn("Could not scan artifact repository " + repository + ": " + e.getMessage());
		}
		return List.of();
	}

	private static List<StalePoolEntry> scan(Path repository, Path file, InputStream in) throws XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		XMLStreamReader reader = factory.createXMLStreamReader(in);
		Map<String, String> outputByClassifier = new HashMap<>();
		List<StalePoolEntry> stale = new ArrayList<>();
		String classifier = null;
		String id = null;
		String version = null;
		boolean folder = false;
		String reference = null;
		try {
			while (reader.hasNext()) {
				int event = reader.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					switch (reader.getLocalName()) {
					case "rule" -> {
						String filter = reader.getAttributeValue(null, "filter");
						Matcher m = filter == null ? null : RULE_CLASSIFIER.matcher(filter);
						if (m != null && m.find() && !filter.contains("format=")) {
							outputByClassifier.putIfAbsent(m.group(1), reader.getAttributeValue(null, "output"));
						}
					}
					case "artifact" -> {
						classifier = reader.getAttributeValue(null, "classifier");
						id = reader.getAttributeValue(null, "id");
						version = reader.getAttributeValue(null, "version");
						folder = false;
						reference = null;
					}
					case "property" -> {
						if (id != null) {
							String name = reader.getAttributeValue(null, "name");
							if (ARTIFACT_FOLDER.equals(name)) {
								folder = Boolean.parseBoolean(reader.getAttributeValue(null, "value"));
							} else if (ARTIFACT_REFERENCE.equals(name)) {
								reference = reader.getAttributeValue(null, "value");
							}
						}
					}
					default -> {
					}
					}
				} else if (event == XMLStreamConstants.END_ELEMENT && "artifact".equals(reader.getLocalName())) {
					if (folder) {
						Path dir = folderLocation(repository, outputByClassifier.get(classifier), id, version,
								reference);
						if (dir != null && !Files.isDirectory(dir)) {
							stale.add(new StalePoolEntry(file, id, version, dir));
						}
					}
					id = null;
				}
			}
		} finally {
			reader.close();
		}
		return stale;
	}

	/**
	 * Mirrors SimpleArtifactRepository.getLocation(): an explicit reference wins,
	 * otherwise the mapping rule's output with its {@code .jar} extension dropped
	 * for a folder-shaped artifact.
	 */
	private static Path folderLocation(Path repository, String output, String id, String version,
			String reference) {
		if (reference != null) {
			return toLocalPath(reference).orElse(null);
		}
		if (output == null || id == null || version == null || !output.startsWith(REPO_URL_PREFIX)) {
			return null;
		}
		String relative = output.substring(REPO_URL_PREFIX.length()).replace("${id}", id).replace("${version}",
				version);
		if (relative.endsWith(".jar")) {
			relative = relative.substring(0, relative.length() - ".jar".length());
		}
		return repository.resolve(relative);
	}
}
