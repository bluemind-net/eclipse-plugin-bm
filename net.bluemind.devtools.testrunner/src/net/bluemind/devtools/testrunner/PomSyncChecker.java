package net.bluemind.devtools.testrunner;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMStandin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import net.bluemind.devtools.testrunner.PomPropertyReader.PomProperties;
import net.bluemind.devtools.testrunner.WorkspaceConfigReader.BmLocationInfo;
import net.bluemind.devtools.testrunner.WorkspaceConfigReader.WorkspaceConfig;

public class PomSyncChecker {

	private static final ILog LOG = Platform.getLog(PomSyncChecker.class);
	private static final String BM_REPO_PATTERN = "forge.bluemind.net/staging/p2/dependencies/";
	private static final AtomicBoolean dialogOpen = new AtomicBoolean();

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

	public static void checkAndPrompt(boolean showIfInSync) {
		if (!dialogOpen.compareAndSet(false, true)) {
			return;
		}

		SyncStatus status = computeStatus();
		if (status == null) {
			if (showIfInSync) {
				Display.getDefault().asyncExec(() -> {
					try {
						Shell shell = getShell();
						if (shell != null) {
							MessageDialog.openInformation(shell, "BlueMind POM Sync",
									"No BlueMind workspace detected. POM sync is not available.");
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

		boolean targetMismatch;
		if (pom.targetRepoUrl() == null) {
			targetMismatch = false;
		} else {
			BmLocationInfo current = ws.bmLocation();
			targetMismatch = current == null
					|| !stripTrailingSlash(current.location()).equals(stripTrailingSlash(pom.targetRepoUrl()));
		}

		String localJvmOptions = PomPropertyReader.readLocalJvmOptions();
		return new SyncStatus(vmMismatch, targetMismatch, localJvmOptions, pom, ws);
	}

	private static void showSyncDialog(Shell shell, SyncStatus status) {
		StringBuilder msg = new StringBuilder();
		msg.append("The workspace settings don't match the global POM:\n\n");

		if (status.vmArgsMismatch) {
			msg.append("\u2022 JVM Arguments (JRE: ").append(status.workspaceConfig.vmName()).append(")\n");
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
			msg.append("\u2022 Target Platform");
			if (noTarget) {
				msg.append(" (will be created)");
			}
			msg.append("\n");
			if (!noTarget) {
				BmLocationInfo current = status.workspaceConfig.bmLocation();
				if (current != null) {
					msg.append("  Current: ").append(current.location()).append("\n");
				} else {
					msg.append("  Current: (no BlueMind location)\n");
				}
			}

			String repoUrl = status.pomProps.targetRepoUrl();
			if (status.pomProps.isFileTarget()) {
				msg.append("  Directory: ").append(repoUrl).append("\n\n");
			} else {
				msg.append("  URL: ").append(repoUrl).append("\n\n");
			}
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
			BmLocationInfo currentLoc = status.workspaceConfig.bmLocation();
			if (status.pomProps.isFileTarget()) {
				applyDirectoryTargetSync(status.pomProps.targetRepoUrl(), currentLoc);
			} else {
				applyHttpTargetSync(status.pomProps.targetRepoUrl(), currentLoc);
			}
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

	private static void applyHttpTargetSync(String repoUrl, BmLocationInfo currentBmLocation) {
		new Job("Updating BlueMind target platform") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					monitor.beginTask("Fetching P2 repository metadata", IProgressMonitor.UNKNOWN);

					URI repoUri = URI.create(repoUrl);
					List<IInstallableUnit> units = queryP2Repository(repoUri, monitor);

					var ctx = Activator.getDefault().getBundle().getBundleContext();
					var ref = ctx.getServiceReference(ITargetPlatformService.class);
					if (ref == null) {
						LOG.warn("ITargetPlatformService not available");
						return Status.error("ITargetPlatformService not available");
					}
					var service = ctx.getService(ref);
					try {
						var target = getOrCreateTarget(service);

						var iuArray = units.toArray(new IInstallableUnit[0]);
						int flags = INCLUDE_REQUIRED | INCLUDE_SOURCE | INCLUDE_CONFIGURE_PHASE;
						ITargetLocation bmLocation = service.newIULocation(iuArray, new URI[] { repoUri }, flags);

						ITargetLocation[] existing = target.getTargetLocations();
						target.setTargetLocations(replaceOrAddBmLocation(existing, bmLocation, currentBmLocation));

						service.saveTargetDefinition(target);
						scheduleLoadWithErrorHandling(target);

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

	private static void applyDirectoryTargetSync(String fileUrl, BmLocationInfo currentBmLocation) {
		String directoryPath = URI.create(fileUrl).getPath();

		if (!Files.isDirectory(Path.of(directoryPath))) {
			Display.getDefault().asyncExec(() -> {
				Shell shell = getShell();
				if (shell != null) {
					MessageDialog.openError(shell, "BlueMind Target Platform",
							"The configured directory does not exist:\n" + directoryPath);
				}
			});
			return;
		}

		new Job("Updating BlueMind target platform (local directory)") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					monitor.beginTask("Configuring local directory target", IProgressMonitor.UNKNOWN);

					var ctx = Activator.getDefault().getBundle().getBundleContext();
					var ref = ctx.getServiceReference(ITargetPlatformService.class);
					if (ref == null) {
						LOG.warn("ITargetPlatformService not available");
						return Status.error("ITargetPlatformService not available");
					}
					var service = ctx.getService(ref);
					try {
						var target = getOrCreateTarget(service);

						ITargetLocation bmLocation = service.newDirectoryLocation(directoryPath);

						ITargetLocation[] existing = target.getTargetLocations();
						target.setTargetLocations(replaceOrAddBmLocation(existing, bmLocation, currentBmLocation));

						service.saveTargetDefinition(target);
						scheduleLoadWithErrorHandling(target);

						LOG.info("Target platform updated with local directory: " + directoryPath);
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

	private static ITargetDefinition getOrCreateTarget(ITargetPlatformService service) throws CoreException {
		var handle = service.getWorkspaceTargetHandle();
		if (handle != null) {
			return handle.getTargetDefinition();
		}
		var target = service.newTarget();
		target.setName("BlueMind");
		return target;
	}

	private static void scheduleLoadWithErrorHandling(ITargetDefinition target) {
		var loadJob = new LoadTargetDefinitionJob(target);
		loadJob.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				var result = event.getResult();
				if (!result.isOK() && !result.matches(IStatus.CANCEL)) {
					Display.getDefault().asyncExec(() -> {
						Shell shell = getShell();
						if (shell != null) {
							MessageDialog.openError(shell, "BlueMind Target Platform",
									"Failed to load target platform:\n" + result.getMessage());
						}
					});
				}
			}
		});
		loadJob.setUser(true);
		loadJob.schedule();
	}

	private static boolean isBmLocation(ITargetLocation loc, BmLocationInfo currentBmLocation) {
		String xml = loc.serialize();
		if (xml != null && xml.contains(BM_REPO_PATTERN)) {
			return true;
		}
		// Detect the previously-set BM location (could be a directory from a prior sync)
		if (currentBmLocation != null && "Directory".equals(loc.getType())) {
			try {
				String locPath = loc.getLocation(true);
				return locPath != null && locPath.equals(currentBmLocation.location());
			} catch (CoreException ignored) {
				return false;
			}
		}
		return false;
	}

	private static ITargetLocation[] replaceOrAddBmLocation(ITargetLocation[] existing,
			ITargetLocation bmLocation, BmLocationInfo currentBmLocation) {
		if (existing == null || existing.length == 0) {
			return new ITargetLocation[] { bmLocation };
		}
		for (int i = 0; i < existing.length; i++) {
			if (isBmLocation(existing[i], currentBmLocation)) {
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
