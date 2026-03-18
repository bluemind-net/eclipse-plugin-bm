package net.bluemind.devtools.testrunner;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMStandin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import net.bluemind.devtools.testrunner.PomPropertyReader.PomProperties;
import net.bluemind.devtools.testrunner.WorkspaceConfigReader.WorkspaceConfig;

public class PomSyncChecker {

	private static final ILog LOG = Platform.getLog(PomSyncChecker.class);
	private static final String BM_REPO_BASE = "https://forge.bluemind.net/staging/p2/dependencies/";
	private static final String BM_REPO_PATTERN = "forge.bluemind.net/staging/p2/dependencies/";
	private static final AtomicBoolean dialogOpen = new AtomicBoolean();

	// IUBundleContainer resolution flags
	private static final int INCLUDE_REQUIRED = 1 << 0;
	private static final int INCLUDE_SOURCE = 1 << 2;
	private static final int INCLUDE_CONFIGURE_PHASE = 1 << 3;

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
		if (!dialogOpen.compareAndSet(false, true)) {
			return;
		}

		SyncStatus status = computeStatus();
		if (status == null) {
			dialogOpen.set(false);
			return;
		}

		if (!status.hasMismatch()) {
			if (showIfInSync) {
				Display.getDefault().asyncExec(() -> {
					try {
						Shell shell = getShell();
						if (shell != null) {
							MessageDialog.openInformation(shell, "BlueMind POM Sync",
									"Workspace settings are in sync with the global POM.");
						}
					} finally {
						dialogOpen.set(false);
					}
				});
			} else {
				dialogOpen.set(false);
			}
			return;
		}

		Display.getDefault().asyncExec(() -> {
			try {
				Shell shell = getShell();
				if (shell != null) {
					showSyncDialog(shell, status);
				}
			} finally {
				dialogOpen.set(false);
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
			boolean noTarget = !status.workspaceConfig.hasTarget();
			msg.append("• Target Platform");
			if (noTarget) {
				msg.append(" (will be created)");
			}
			msg.append("\n");
			if (!noTarget) {
				String currentUrl = status.workspaceConfig.targetRepoUrl();
				msg.append("  Current: ").append(currentUrl != null ? currentUrl : "(no BlueMind repo)").append("\n");
			}
			msg.append("  URL: ").append(BM_REPO_BASE).append(status.pomProps.targetPlatformVersion())
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
			applyTargetSync(status.pomProps.targetPlatformVersion());
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

	private static void applyTargetSync(String newVersion) {
		new Job("Updating BlueMind target platform") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					monitor.beginTask("Fetching P2 repository metadata", IProgressMonitor.UNKNOWN);

					URI repoUri = URI.create(BM_REPO_BASE + newVersion);
					List<IInstallableUnit> units = queryP2Repository(repoUri, monitor);

					var ctx = Activator.getDefault().getBundle().getBundleContext();
					var ref = ctx.getServiceReference(ITargetPlatformService.class);
					if (ref == null) {
						LOG.warn("ITargetPlatformService not available");
						return Status.error("ITargetPlatformService not available");
					}
					var service = ctx.getService(ref);
					try {
						var handle = service.getWorkspaceTargetHandle();
						var target = handle != null
								? handle.getTargetDefinition()
								: service.newTarget();
						if (handle == null) {
							target.setName("BlueMind");
						}

						var iuArray = units.toArray(new IInstallableUnit[0]);
						int flags = INCLUDE_REQUIRED | INCLUDE_SOURCE | INCLUDE_CONFIGURE_PHASE;
						ITargetLocation bmLocation = service.newIULocation(iuArray, new URI[] { repoUri }, flags);

						ITargetLocation[] existing = target.getTargetLocations();
						target.setTargetLocations(replaceOrAddBmLocation(existing, bmLocation));

						service.saveTargetDefinition(target);
						new LoadTargetDefinitionJob(target).schedule();

						LOG.info("Target platform updated with " + units.size() + " units from " + repoUri);
					} finally {
						ctx.ungetService(ref);
					}

					return Status.OK_STATUS;
				} catch (Exception e) {
					LOG.error("Failed to update target platform", e);
					return Status.error("Failed to update target platform", e);
				} finally {
					monitor.done();
				}
			}
		}.schedule();
	}

	private static ITargetLocation[] replaceOrAddBmLocation(ITargetLocation[] existing, ITargetLocation bmLocation) {
		if (existing == null || existing.length == 0) {
			return new ITargetLocation[] { bmLocation };
		}
		for (int i = 0; i < existing.length; i++) {
			String xml = existing[i].serialize();
			if (xml != null && xml.contains(BM_REPO_PATTERN)) {
				existing[i] = bmLocation;
				return existing;
			}
		}
		var result = new ITargetLocation[existing.length + 1];
		System.arraycopy(existing, 0, result, 0, existing.length);
		result[existing.length] = bmLocation;
		return result;
	}

	private static List<IInstallableUnit> queryP2Repository(URI repoUri, IProgressMonitor monitor) {
		var deduplicated = new LinkedHashMap<String, IInstallableUnit>();
		try {
			var ctx = Activator.getDefault().getBundle().getBundleContext();
			var agentRef = ctx.getServiceReference(IProvisioningAgent.class);
			if (agentRef == null) {
				LOG.warn("IProvisioningAgent not available");
				return List.of();
			}
			var agent = ctx.getService(agentRef);
			try {
				var repoManager = (IMetadataRepositoryManager) agent
						.getService(IMetadataRepositoryManager.class.getName());
				if (repoManager == null) {
					LOG.warn("IMetadataRepositoryManager not available");
					return List.of();
				}
				var repo = repoManager.loadRepository(repoUri, monitor);
				var result = repo.query(QueryUtil.createIUAnyQuery(), monitor);
				for (IInstallableUnit iu : result) {
					String id = iu.getId();
					if (id.startsWith("a.jre.")) continue;
					if (id.endsWith(".feature.jar")) continue;
					if ("true".equals(iu.getProperty("org.eclipse.equinox.p2.type.category"))) continue;
					if (iu.getFilter() != null) continue;

					var existing = deduplicated.get(id);
					if (existing == null || iu.getVersion().compareTo(existing.getVersion()) > 0) {
						deduplicated.put(id, iu);
					}
				}
			} finally {
				ctx.ungetService(agentRef);
			}
		} catch (Exception e) {
			LOG.error("Failed to query P2 repository: " + repoUri, e);
		}
		var units = new ArrayList<>(deduplicated.values());
		units.sort(Comparator.comparing(IInstallableUnit::getId));
		return units;
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
