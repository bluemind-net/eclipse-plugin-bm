package net.bluemind.devtools.testrunner;

import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMStandin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import net.bluemind.devtools.testrunner.PomPropertyReader.PomProperties;
import net.bluemind.devtools.testrunner.WorkspaceConfigReader.WorkspaceConfig;

public class PomSyncChecker {

	private static final ILog LOG = Platform.getLog(PomSyncChecker.class);
	private static final String BM_REPO_BASE = "https://forge.bluemind.net/staging/p2/dependencies/";

	public record SyncStatus(
			boolean vmArgsMismatch,
			boolean targetPlatformMismatch,
			String localJvmOptions,
			PomProperties pomProps,
			WorkspaceConfig workspaceConfig) {

		boolean hasMismatch() {
			return vmArgsMismatch || targetPlatformMismatch;
		}

		boolean hasLocalJvmOptions() {
			return localJvmOptions != null && !localJvmOptions.isEmpty();
		}
	}

	/**
	 * Main entry point. Computes sync status and prompts the user if there's a
	 * mismatch. Can be called from any thread.
	 *
	 * @param showIfInSync if true, shows an "all good" message when in sync
	 */
	public static void checkAndPrompt(boolean showIfInSync) {
		SyncStatus status = computeStatus();
		if (status == null) {
			return;
		}

		if (!status.hasMismatch()) {
			if (showIfInSync) {
				Display.getDefault().asyncExec(() -> {
					Shell shell = getShell();
					if (shell != null) {
						MessageDialog.openInformation(shell, "BlueMind POM Sync",
								"Workspace settings are in sync with the global POM.");
					}
				});
			}
			return;
		}

		Display.getDefault().asyncExec(() -> {
			Shell shell = getShell();
			if (shell != null) {
				showSyncDialog(shell, status);
			}
		});
	}

	static SyncStatus computeStatus() {
		var pomPathOpt = PomPropertyReader.findGlobalPom();
		if (pomPathOpt.isEmpty()) {
			return null;
		}

		var pomPropsOpt = PomPropertyReader.readProperties(pomPathOpt.get());
		if (pomPropsOpt.isEmpty()) {
			return null;
		}

		var wsConfigOpt = WorkspaceConfigReader.read();
		if (wsConfigOpt.isEmpty()) {
			return null;
		}

		PomProperties pom = pomPropsOpt.get();
		WorkspaceConfig ws = wsConfigOpt.get();

		boolean vmMismatch = !normalize(pom.resolvedTestArgLine()).equals(normalize(ws.vmArgs()));

		String expectedTargetUrl = BM_REPO_BASE + pom.targetPlatformVersion();
		boolean targetMismatch = ws.targetRepoUrl() == null
				|| !stripTrailingSlash(ws.targetRepoUrl()).equals(stripTrailingSlash(expectedTargetUrl));

		String localJvmOptions = PomPropertyReader.readLocalJvmOptions();
		return new SyncStatus(vmMismatch, targetMismatch, localJvmOptions, pom, ws);
	}

	private static void showSyncDialog(Shell shell, SyncStatus status) {
		StringBuilder msg = new StringBuilder();
		msg.append("The workspace settings don't match the global POM:\n\n");

		if (status.vmArgsMismatch) {
			msg.append("• JVM Arguments (JRE: ").append(status.workspaceConfig.vmName()).append(")\n");
			msg.append("  Will be updated to match tycho.testArgLine from POM\n");
			msg.append("  (docker.devenv.tag=").append(status.pomProps.dockerDevenvTag()).append(")\n");
			if (status.hasLocalJvmOptions()) {
				long count = status.localJvmOptions.chars().filter(c -> c == ' ').count() + 1;
				msg.append("  + ").append(count).append(" option(s) from ~/.config/bluemind/jvm.options\n");
			}
			msg.append("\n");
		}

		if (status.targetPlatformMismatch) {
			msg.append("• Target Platform\n");
			String currentUrl = status.workspaceConfig.targetRepoUrl();
			msg.append("  Current: ").append(currentUrl != null ? currentUrl : "(not set)").append("\n");
			msg.append("  Expected: ").append(BM_REPO_BASE).append(status.pomProps.targetPlatformVersion())
					.append("\n\n");
		}

		msg.append("Update workspace settings?");

		boolean apply = MessageDialog.openQuestion(shell, "BlueMind POM Sync", msg.toString());
		if (apply) {
			applySync(status);
		}
	}

	private static void applySync(SyncStatus status) {
		if (status.vmArgsMismatch) {
			applyVmArgsSync(status.pomProps.resolvedTestArgLine());
		}
		if (status.targetPlatformMismatch) {
			applyTargetSync(status.workspaceConfig.targetFilePath(), status.pomProps.targetPlatformVersion());
		}
	}

	private static void applyVmArgsSync(String resolvedTestArgLine) {
		try {
			IVMInstall defaultVm = JavaRuntime.getDefaultVMInstall();
			if (defaultVm == null) {
				LOG.warn("No default JRE to update");
				return;
			}

			VMStandin standin = new VMStandin(defaultVm);
			String[] args = resolvedTestArgLine.split("\\s+");
			standin.setVMArguments(args);
			standin.convertToRealVM();
			LOG.info("Updated JRE VM arguments for: " + defaultVm.getName());
		} catch (Exception e) {
			LOG.error("Failed to update JRE VM arguments", e);
		}
	}

	private static void applyTargetSync(Path targetFilePath, String newVersion) {
		if (targetFilePath == null) {
			LOG.warn("No target file path to update");
			return;
		}

		try {
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
					.parse(targetFilePath.toFile());

			// Find the <location> element that contains the BlueMind repository
			NodeList locations = doc.getElementsByTagName("location");
			boolean updated = false;
			for (int i = 0; i < locations.getLength(); i++) {
				Element loc = (Element) locations.item(i);
				NodeList repos = loc.getElementsByTagName("repository");
				for (int j = 0; j < repos.getLength(); j++) {
					Element repo = (Element) repos.item(j);
					String repoUrl = repo.getAttribute("location");
					if (repoUrl != null && repoUrl.contains("forge.bluemind.net/staging/p2/dependencies/")) {
						repo.setAttribute("location", BM_REPO_BASE + newVersion);
						updated = true;

						// Reset all unit versions in this location to 0.0.0
						// so they resolve to whatever is available in the new repo
						NodeList units = loc.getElementsByTagName("unit");
						for (int k = 0; k < units.getLength(); k++) {
							((Element) units.item(k)).setAttribute("version", "0.0.0");
						}
					}
				}
			}

			if (!updated) {
				LOG.warn("No BlueMind repository found in target file: " + targetFilePath);
				return;
			}

			// Write back the modified XML
			var transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.setOutputProperty(OutputKeys.STANDALONE, "no");
			transformer.transform(new DOMSource(doc), new StreamResult(targetFilePath.toFile()));

			LOG.info("Updated target platform repository URL to: " + BM_REPO_BASE + newVersion);

			// Reload the target platform
			reloadTargetPlatform();
		} catch (Exception e) {
			LOG.error("Failed to update target platform", e);
		}
	}

	private static void reloadTargetPlatform() {
		try {
			var ctx = Activator.getDefault().getBundle().getBundleContext();
			var ref = ctx
					.getServiceReference(org.eclipse.pde.core.target.ITargetPlatformService.class);
			if (ref == null) {
				LOG.warn("ITargetPlatformService not available for reload");
				return;
			}

			var service = ctx.getService(ref);
			try {
				var handle = service.getWorkspaceTargetHandle();
				var target = handle.getTargetDefinition();
				new org.eclipse.pde.core.target.LoadTargetDefinitionJob(target).schedule();
				LOG.info("Target platform reload scheduled");
			} finally {
				ctx.ungetService(ref);
			}
		} catch (Exception e) {
			LOG.error("Failed to reload target platform", e);
		}
	}

	private static String normalize(String s) {
		return s == null ? "" : s.trim().replaceAll("\\s+", " ");
	}

	private static String stripTrailingSlash(String s) {
		return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}

	private static Shell getShell() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		return window != null ? window.getShell() : null;
	}
}
