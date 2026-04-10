package net.bluemind.devtools.testrunner;

import java.util.Optional;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.core.target.ITargetPlatformService;

public class WorkspaceConfigReader {

	private static final ILog LOG = Platform.getLog(WorkspaceConfigReader.class);
	private static final String BM_REPO_PATTERN = "forge.bluemind.net/staging/p2/dependencies/";

	public record BmLocationInfo(String mode, String location) {
	}

	public record WorkspaceConfig(String vmArgs, BmLocationInfo bmLocation, String vmName, boolean hasTarget) {
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

			BmLocationInfo bmLocation = null;
			boolean hasTarget = false;

			var ctx = Activator.getDefault().getBundle().getBundleContext();
			var ref = ctx.getServiceReference(ITargetPlatformService.class);
			if (ref != null) {
				var service = ctx.getService(ref);
				try {
					var handle = service.getWorkspaceTargetHandle();
					if (handle != null) {
						hasTarget = true;
						var target = handle.getTargetDefinition();
						bmLocation = findBmLocationInfo(target.getTargetLocations());
					}
				} finally {
					ctx.ungetService(ref);
				}
			}

			return Optional.of(new WorkspaceConfig(vmArgs, bmLocation, defaultVm.getName(), hasTarget));
		} catch (Exception e) {
			LOG.error("Failed to read workspace config", e);
			return Optional.empty();
		}
	}

	static BmLocationInfo findBmLocationInfo(ITargetLocation[] locations) {
		if (locations == null) return null;
		BmLocationInfo directoryCandidate = null;
		for (ITargetLocation loc : locations) {
			String xml = loc.serialize();
			if (xml != null && xml.contains(BM_REPO_PATTERN)) {
				int idx = xml.indexOf(BM_REPO_PATTERN);
				int start = xml.lastIndexOf("=\"", idx);
				if (start < 0) continue;
				start += 2;
				int end = xml.indexOf('"', idx);
				if (end < 0) continue;
				return new BmLocationInfo("http", xml.substring(start, end));
			}

			if ("Directory".equals(loc.getType()) && directoryCandidate == null) {
				try {
					String locPath = loc.getLocation(true);
					if (locPath != null) {
						directoryCandidate = new BmLocationInfo("directory", locPath);
					}
				} catch (CoreException ignored) {
				}
			}
		}
		return directoryCandidate;
	}
}
