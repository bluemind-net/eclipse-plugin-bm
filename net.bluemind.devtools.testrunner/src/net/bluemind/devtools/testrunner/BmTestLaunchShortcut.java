package net.bluemind.devtools.testrunner;

import java.util.Set;
import java.util.jar.Manifest;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.pde.ui.launcher.JUnitWorkbenchLaunchShortcut;

public class BmTestLaunchShortcut extends JUnitWorkbenchLaunchShortcut {

	private static final ILog LOG = Platform.getLog(BmTestLaunchShortcut.class);
	private static final String BM_TEST_FEATURE = "net.bluemind.tests.feature:default";
	public static final String MCP_REQUEST_ID_ATTR = "net.bluemind.devtools.testrunner.mcpRequestId";

	private static final ThreadLocal<String> PENDING_MCP_ID = new ThreadLocal<>();

	@Override
	protected ILaunchConfigurationWorkingCopy createLaunchConfiguration(IJavaElement element) throws CoreException {
		var wc = super.createLaunchConfiguration(element);

		var project = element.getJavaProject().getProject();
		var info = readBundleInfo(project);

		wc.setAttribute("useCustomFeatures", true);
		wc.setAttribute("selected_features", Set.of(BM_TEST_FEATURE));
		wc.setAttribute("additional_plugins", buildAdditionalPlugins(info));

		wc.setAttribute("featureDefaultLocation", "workspace");
		wc.setAttribute("featurePluginResolution", "workspace");

		// Headless core test runner, not the PDE default product-based launch
		wc.setAttribute("application", "org.eclipse.pde.junit.runtime.coretestapplication");
		wc.setAttribute("product", "net.bluemind.application.launcher.bmProduct");
		wc.setAttribute("useProduct", false);

		String mcpId = PENDING_MCP_ID.get();
		if (mcpId != null) {
			wc.setAttribute(MCP_REQUEST_ID_ATTR, mcpId);
		}

		return wc;
	}

	public static void launchElement(IType type, String methodName, String mode) {
		launchElement(type, methodName, mode, null);
	}

	public static void launchElement(IType type, String methodName, String mode, String mcpRequestId) {
		try {
			if (Flags.isAbstract(type.getFlags())) {
				LOG.warn("Cannot run tests on abstract class: " + type.getFullyQualifiedName());
				return;
			}
		} catch (Exception ignored) {
			return;
		}
		IJavaElement element = methodName != null ? findTestMethod(type, methodName) : type;
		PENDING_MCP_ID.set(mcpRequestId);
		try {
			new BmTestLaunchShortcut().launch(new StructuredSelection(element), mode);
		} finally {
			PENDING_MCP_ID.remove();
		}
	}

	public static void launchProject(IJavaElement projectElement, String mode, String mcpRequestId) {
		PENDING_MCP_ID.set(mcpRequestId);
		try {
			new BmTestLaunchShortcut().launch(new StructuredSelection(projectElement), mode);
		} finally {
			PENDING_MCP_ID.remove();
		}
	}

	private static IJavaElement findTestMethod(IType type, String methodName) {
		try {
			for (var method : type.getMethods()) {
				if (method.getElementName().equals(methodName)) {
					return method;
				}
			}
		} catch (JavaModelException ignored) {
			// fallback to type
		}
		return type;
	}

	private record BundleInfo(String symbolicName, String version, String fragmentHost) {
	}

	private BundleInfo readBundleInfo(IProject project) {
		String name = project.getName();
		String version = "0.0.0";
		String fragmentHost = null;
		try {
			var manifest = project.getFile("META-INF/MANIFEST.MF");
			if (manifest.exists()) {
				try (var is = manifest.getContents()) {
					var attrs = new Manifest(is).getMainAttributes();
					String bsn = attrs.getValue("Bundle-SymbolicName");
					if (bsn != null) {
						name = bsn.split(";")[0].trim();
					}
					String ver = attrs.getValue("Bundle-Version");
					if (ver != null) {
						version = ver;
					}
					String fh = attrs.getValue("Fragment-Host");
					if (fh != null) {
						fragmentHost = fh.split(";")[0].trim();
					}
				}
			}
		} catch (Exception e) {
			LOG.warn("Could not read MANIFEST.MF for " + project.getName() + ": " + e.getMessage());
		}
		return new BundleInfo(name, version, fragmentHost);
	}

	private Set<String> buildAdditionalPlugins(BundleInfo info) {
		String entry = info.symbolicName + ":" + info.version + ":default:true:default:default";
		if (info.fragmentHost != null) {
			String host = info.fragmentHost + ":0.0.0:default:true:default:default";
			return Set.of(entry, host);
		}
		return Set.of(entry);
	}
}
