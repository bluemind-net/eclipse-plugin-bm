package net.bluemind.devtools.testrunner;

import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.ILaunchShortcut2;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;

public class BmTestLaunchShortcut implements ILaunchShortcut2 {

	private static final ILog LOG = Platform.getLog(BmTestLaunchShortcut.class);
	private static final String LAUNCH_CONFIG_TYPE = "org.eclipse.pde.ui.JunitLaunchConfig";
	private static final String BM_TEST_FEATURE = "net.bluemind.tests.feature:default";

	@Override
	public void launch(ISelection selection, String mode) {
		IProject project = getProject(selection);
		if (project != null) {
			launch(project, mode);
		}
	}

	@Override
	public void launch(IEditorPart editor, String mode) {
	}

	@Override
	public ILaunchConfiguration[] getLaunchConfigurations(ISelection selection) {
		IProject project = getProject(selection);
		if (project == null) {
			return null;
		}
		try {
			ILaunchConfiguration config = findOrCreateConfig(project);
			return config != null ? new ILaunchConfiguration[] { config } : null;
		} catch (CoreException e) {
			LOG.log(new Status(Status.ERROR, Activator.PLUGIN_ID, "Failed to get launch config", e));
			return null;
		}
	}

	@Override
	public ILaunchConfiguration[] getLaunchConfigurations(IEditorPart editor) {
		return null;
	}

	@Override
	public IResource getLaunchableResource(ISelection selection) {
		return getProject(selection);
	}

	@Override
	public IResource getLaunchableResource(IEditorPart editor) {
		return null;
	}

	private void launch(IProject project, String mode) {
		try {
			ILaunchConfiguration config = findOrCreateConfig(project);
			if (config != null) {
				config.launch(mode, null);
			}
		} catch (CoreException e) {
			LOG.log(new Status(Status.ERROR, Activator.PLUGIN_ID, "Failed to launch tests", e));
		}
	}

	private ILaunchConfiguration findOrCreateConfig(IProject project) throws CoreException {
		String bundleName = project.getName();
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationType type = manager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE);

		if (type == null) {
			LOG.warn("Launch config type not found: " + LAUNCH_CONFIG_TYPE);
			return null;
		}

		for (ILaunchConfiguration existing : manager.getLaunchConfigurations(type)) {
			if (existing.getName().equals(bundleName)) {
				LOG.info("Reusing launch configuration: " + bundleName);
				return existing;
			}
		}

		LOG.info("Creating launch configuration: " + bundleName);
		ILaunchConfigurationWorkingCopy wc = type.newInstance(null, bundleName);

		String version = getBundleVersion(project);

		// Plug-ins tab: "Launch with: features selected below"
		wc.setAttribute("useCustomFeatures", true);
		wc.setAttribute("selected_features", Set.of(BM_TEST_FEATURE));
		wc.setAttribute("additional_plugins",
				Set.of(bundleName + ":" + version + ":default:true:default:default"));

		// Feature resolution
		wc.setAttribute("featureDefaultLocation", "workspace");
		wc.setAttribute("featurePluginResolution", "workspace");
		wc.setAttribute("automaticAdd", true);
		wc.setAttribute("automaticValidate", true);
		wc.setAttribute("includeOptional", true);
		wc.setAttribute("default", false);
		wc.setAttribute("checked", "[NONE]");

		// Main tab
		wc.setAttribute("org.eclipse.jdt.launching.PROJECT_ATTR", bundleName);
		wc.setAttribute("org.eclipse.jdt.junit.CONTAINER", "=" + bundleName);
		wc.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit5");
		wc.setAttribute("org.eclipse.jdt.launching.MAIN_TYPE", "");
		wc.setAttribute("org.eclipse.jdt.junit.TESTNAME", "");
		wc.setAttribute("org.eclipse.jdt.junit.KEEPRUNNING_ATTR", false);

		// Application / product
		wc.setAttribute("application", "org.eclipse.pde.junit.runtime.coretestapplication");
		wc.setAttribute("product", "net.bluemind.application.launcher.bmProduct");
		wc.setAttribute("useProduct", false);

		// Arguments
		wc.setAttribute("org.eclipse.jdt.launching.PROGRAM_ARGUMENTS",
				"-os ${target.os} -ws ${target.ws} -arch ${target.arch} -nl ${target.nl} -consoleLog");
		wc.setAttribute("org.eclipse.jdt.launching.VM_ARGUMENTS",
				"-Dorg.eclipse.swt.graphics.Resource.reportNonDisposed=true -ea");
		wc.setAttribute("append.args", true);
		wc.setAttribute("bootstrap", "");

		// Configuration
		wc.setAttribute("clearConfig", true);
		wc.setAttribute("clearws", true);
		wc.setAttribute("clearwslog", false);
		wc.setAttribute("askclear", false);
		wc.setAttribute("configLocation",
				"${workspace_loc}/.metadata/.plugins/org.eclipse.pde.core/pde-junit");
		wc.setAttribute("location", "${workspace_loc}/../junit-workspace");
		wc.setAttribute("useDefaultConfig", true);
		wc.setAttribute("useDefaultConfigArea", false);
		wc.setAttribute("templateConfig", "${target_home}/configuration/config.ini");

		// JDT / Runtime
		wc.setAttribute("org.eclipse.jdt.launching.ATTR_ATTR_USE_ARGFILE", false);
		wc.setAttribute("org.eclipse.jdt.launching.ATTR_SHOW_CODEDETAILS_IN_EXCEPTION_MESSAGES", true);
		wc.setAttribute("org.eclipse.jdt.launching.ATTR_USE_START_ON_FIRST_THREAD", true);
		wc.setAttribute("org.eclipse.jdt.launching.JRE_CONTAINER",
				"org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-25");
		wc.setAttribute("org.eclipse.debug.core.ATTR_FORCE_SYSTEM_CONSOLE_ENCODING", false);
		wc.setAttribute("run_in_ui_thread", true);
		wc.setAttribute("show_selected_only", false);
		wc.setAttribute("tracing", false);
		wc.setAttribute("pde.version", "3.3");

		// Source
		wc.setAttribute("org.eclipse.jdt.launching.SOURCE_PATH_PROVIDER",
				"org.eclipse.pde.ui.workbenchClasspathProvider");

		// Mapped resources
		wc.setAttribute("org.eclipse.debug.core.MAPPED_RESOURCE_PATHS",
				java.util.List.of("/" + bundleName));
		wc.setAttribute("org.eclipse.debug.core.MAPPED_RESOURCE_TYPES",
				java.util.List.of("4"));

		return wc.doSave();
	}

	private String getBundleVersion(IProject project) {
		try {
			var manifest = project.getFile("META-INF/MANIFEST.MF");
			if (manifest.exists()) {
				try (var is = manifest.getContents()) {
					var attrs = new java.util.jar.Manifest(is).getMainAttributes();
					String version = attrs.getValue("Bundle-Version");
					if (version != null) {
						return version;
					}
				}
			}
		} catch (Exception e) {
			LOG.warn("Could not read bundle version for " + project.getName() + ": " + e.getMessage());
		}
		return "0.0.0";
	}

	private IProject getProject(ISelection selection) {
		if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) {
			return null;
		}

		Object element = structured.getFirstElement();

		if (element instanceof IProject project) {
			return project;
		}

		if (element instanceof IResource resource) {
			return resource.getProject();
		}

		if (element instanceof org.eclipse.core.runtime.IAdaptable adaptable) {
			IResource resource = adaptable.getAdapter(IResource.class);
			if (resource != null) {
				return resource.getProject();
			}
		}

		return null;
	}
}
