package net.bluemind.devtools.testrunner;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class WorkspaceConfigReader {

	private static final ILog LOG = Platform.getLog(WorkspaceConfigReader.class);
	private static final String BM_REPO_PATTERN = "forge.bluemind.net/staging/p2/dependencies/";

	public record WorkspaceConfig(String vmArgs, String targetRepoUrl, String vmName, Path targetFilePath) {
	}

	public static Optional<WorkspaceConfig> read() {
		try {
			IVMInstall defaultVm = JavaRuntime.getDefaultVMInstall();
			if (defaultVm == null) {
				LOG.warn("No default JRE configured");
				return Optional.empty();
			}

			String vmArgs = "";
			String[] args = defaultVm.getVMArguments();
			if (args != null) {
				vmArgs = String.join(" ", args);
			}

			TargetInfo targetInfo = readTargetInfo();

			return Optional.of(new WorkspaceConfig(vmArgs,
					targetInfo != null ? targetInfo.repoUrl : null,
					defaultVm.getName(),
					targetInfo != null ? targetInfo.filePath : null));
		} catch (Exception e) {
			LOG.error("Failed to read workspace config", e);
			return Optional.empty();
		}
	}

	private record TargetInfo(String repoUrl, Path filePath) {
	}

	/**
	 * Finds the active .target file via PDE prefs and parses it to extract the
	 * BlueMind repository URL.
	 */
	private static TargetInfo readTargetInfo() {
		try {
			Path workspaceRoot = Path.of(Platform.getInstanceLocation().getURL().toURI());
			Path pdePrefs = workspaceRoot
					.resolve(".metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.pde.core.prefs");

			if (!Files.exists(pdePrefs)) {
				LOG.warn("PDE core prefs not found: " + pdePrefs);
				return null;
			}

			Properties props = new Properties();
			try (var in = new FileInputStream(pdePrefs.toFile())) {
				props.load(in);
			}

			String handle = props.getProperty("workspace_target_handle");
			if (handle == null) {
				LOG.warn("No workspace_target_handle in PDE prefs");
				return null;
			}

			// handle format: "local:TIMESTAMP.target"
			int colonIdx = handle.indexOf(':');
			if (colonIdx < 0) {
				LOG.warn("Unexpected target handle format: " + handle);
				return null;
			}
			String filename = handle.substring(colonIdx + 1);

			Path targetFile = workspaceRoot
					.resolve(".metadata/.plugins/org.eclipse.pde.core/.local_targets/" + filename);

			if (!Files.exists(targetFile)) {
				LOG.warn("Target file not found: " + targetFile);
				return null;
			}

			String repoUrl = parseTargetRepoUrl(targetFile);
			return new TargetInfo(repoUrl, targetFile);
		} catch (Exception e) {
			LOG.error("Failed to read target platform info", e);
			return null;
		}
	}

	/**
	 * Parses a .target XML file and extracts the BlueMind P2 repository URL.
	 */
	static String parseTargetRepoUrl(Path targetFile) {
		try {
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
					.parse(targetFile.toFile());
			NodeList repos = doc.getElementsByTagName("repository");
			for (int i = 0; i < repos.getLength(); i++) {
				Element repo = (Element) repos.item(i);
				String location = repo.getAttribute("location");
				if (location != null && location.contains(BM_REPO_PATTERN)) {
					return location;
				}
			}
		} catch (Exception e) {
			LOG.error("Failed to parse target file: " + targetFile, e);
		}
		return null;
	}
}
