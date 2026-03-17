package net.bluemind.devtools.testrunner;

import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.Flags;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.ILaunchShortcut2;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;

public class BmTestLaunchShortcut implements ILaunchShortcut2 {

	private static final ILog LOG = Platform.getLog(BmTestLaunchShortcut.class);
	private static final String LAUNCH_CONFIG_TYPE = "org.eclipse.pde.ui.JunitLaunchConfig";
	private static final String BM_TEST_FEATURE = "net.bluemind.tests.feature:default";

	@Override
	public void launch(ISelection selection, String mode) {
		// Try Java element first (method, type, compilation unit from Outline/Package Explorer)
		IJavaElement javaElement = getJavaElement(selection);
		if (javaElement != null) {
			launchJavaElement(javaElement, mode);
			return;
		}
		// Fallback to project level
		IProject project = getProject(selection);
		if (project != null) {
			launchProject(project, mode);
		}
	}

	@Override
	public void launch(IEditorPart editor, String mode) {
		try {
			IJavaElement input = JavaUI.getEditorInputJavaElement(editor.getEditorInput());
			if (!(input instanceof ICompilationUnit cu)) {
				return;
			}

			// Get element at cursor position
			var sel = editor.getSite().getSelectionProvider().getSelection();
			if (sel instanceof ITextSelection textSel) {
				IJavaElement element = cu.getElementAt(textSel.getOffset());
				if (element instanceof IMethod || element instanceof IType) {
					launchJavaElement(element, mode);
					return;
				}
			}

			// Fallback: launch the primary type
			IType[] types = cu.getTypes();
			if (types.length > 0) {
				launchJavaElement(types[0], mode);
			}
		} catch (Exception e) {
			LOG.log(new Status(Status.ERROR, Activator.PLUGIN_ID, "Failed to launch from editor", e));
		}
	}

	@Override
	public ILaunchConfiguration[] getLaunchConfigurations(ISelection selection) {
		try {
			IJavaElement javaElement = getJavaElement(selection);
			if (javaElement != null) {
				var resolved = resolveElement(javaElement);
				if (resolved != null) {
					ILaunchConfiguration config = findOrCreateConfig(
							resolved.type.getJavaProject().getProject(), resolved.type, resolved.methodName);
					return config != null ? new ILaunchConfiguration[] { config } : null;
				}
			}
			IProject project = getProject(selection);
			if (project != null) {
				ILaunchConfiguration config = findOrCreateConfig(project, null, null);
				return config != null ? new ILaunchConfiguration[] { config } : null;
			}
		} catch (CoreException e) {
			LOG.log(new Status(Status.ERROR, Activator.PLUGIN_ID, "Failed to get launch config", e));
		}
		return null;
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

	/**
	 * Public entry point for launching a specific type/method, used by the code
	 * mining provider.
	 */
	public static void launchElement(IType type, String methodName, String mode) {
		try {
			if (Flags.isAbstract(type.getFlags())) {
				LOG.warn("Cannot run tests on abstract class: " + type.getFullyQualifiedName());
				return;
			}
		} catch (Exception e) {
			return;
		}
		new BmTestLaunchShortcut().launchInJob(type.getJavaProject().getProject(), type, methodName, mode);
	}

	private record ResolvedElement(IType type, String methodName) {
	}

	private ResolvedElement resolveElement(IJavaElement element) {
		try {
			if (element instanceof IMethod method) {
				IType declaring = method.getDeclaringType();
				if (Flags.isAbstract(declaring.getFlags())) {
					LOG.warn("Cannot run tests on abstract class: " + declaring.getFullyQualifiedName());
					return null;
				}
				return new ResolvedElement(declaring, method.getElementName());
			} else if (element instanceof IType type) {
				if (Flags.isAbstract(type.getFlags())) {
					LOG.warn("Cannot run tests on abstract class: " + type.getFullyQualifiedName());
					return null;
				}
				return new ResolvedElement(type, null);
			} else if (element instanceof ICompilationUnit cu) {
				IType[] types = cu.getTypes();
				if (types.length > 0 && !Flags.isAbstract(types[0].getFlags())) {
					return new ResolvedElement(types[0], null);
				}
			}
		} catch (Exception e) {
			LOG.warn("Failed to resolve Java element: " + e.getMessage());
		}
		return null;
	}

	private void launchJavaElement(IJavaElement element, String mode) {
		var resolved = resolveElement(element);
		if (resolved == null) {
			return;
		}
		launchInJob(resolved.type.getJavaProject().getProject(), resolved.type, resolved.methodName, mode);
	}

	private void launchProject(IProject project, String mode) {
		launchInJob(project, null, null, mode);
	}

	private void launchInJob(IProject project, IType type, String methodName, String mode) {
		String label = type != null
				? "Launching BM test: " + type.getElementName() + (methodName != null ? "." + methodName : "")
				: "Launching BM tests: " + project.getName();
		org.eclipse.core.runtime.jobs.Job job = new org.eclipse.core.runtime.jobs.Job(label) {
			@Override
			protected org.eclipse.core.runtime.IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
				try {
					ILaunchConfiguration config = findOrCreateConfig(project, type, methodName);
					if (config != null) {
						config.launch(mode, null);
					}
				} catch (CoreException e) {
					LOG.log(new Status(Status.ERROR, Activator.PLUGIN_ID, "Failed to launch tests", e));
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
	}

	private ILaunchConfiguration findOrCreateConfig(IProject project, IType type, String methodName)
			throws CoreException {
		BundleInfo info = readBundleInfo(project);
		String projectName = project.getName();

		// Config name depends on granularity
		String configName = projectName;
		if (type != null) {
			configName = projectName + " - " + type.getElementName();
			if (methodName != null) {
				configName += "." + methodName;
			}
		}

		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationType lcType = manager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE);

		if (lcType == null) {
			LOG.warn("Launch config type not found: " + LAUNCH_CONFIG_TYPE);
			return null;
		}

		for (ILaunchConfiguration existing : manager.getLaunchConfigurations(lcType)) {
			if (existing.getName().equals(configName)) {
				LOG.info("Deleting stale launch configuration: " + configName);
				existing.delete();
				break;
			}
		}

		LOG.info("Creating launch configuration: " + configName);
		ILaunchConfigurationWorkingCopy wc = lcType.newInstance(null, configName);

		Set<String> additionalPlugins;
		if (info.fragmentHost != null) {
			// Fragment: include both the fragment and its host bundle
			String fragment = info.symbolicName + ":" + info.version + ":default:true:default:default";
			String host = info.fragmentHost + ":0.0.0:default:true:default:default";
			additionalPlugins = Set.of(fragment, host);
		} else {
			additionalPlugins = Set.of(
					info.symbolicName + ":" + info.version + ":default:true:default:default");
		}

		// Plug-ins tab: "Launch with: features selected below"
		wc.setAttribute("useCustomFeatures", true);
		wc.setAttribute("selected_features", Set.of(BM_TEST_FEATURE));
		wc.setAttribute("additional_plugins", additionalPlugins);

		// Feature resolution
		wc.setAttribute("featureDefaultLocation", "workspace");
		wc.setAttribute("featurePluginResolution", "workspace");
		wc.setAttribute("automaticAdd", true);
		wc.setAttribute("automaticValidate", true);
		wc.setAttribute("includeOptional", true);
		wc.setAttribute("default", false);
		wc.setAttribute("checked", "[NONE]");

		// Main tab — depends on granularity
		wc.setAttribute("org.eclipse.jdt.launching.PROJECT_ATTR", projectName);
		wc.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit5");
		wc.setAttribute("org.eclipse.jdt.junit.KEEPRUNNING_ATTR", false);

		if (type != null) {
			// Class or method level: use type handle as container
			wc.setAttribute("org.eclipse.jdt.junit.CONTAINER", type.getHandleIdentifier());
			wc.setAttribute("org.eclipse.jdt.launching.MAIN_TYPE", type.getFullyQualifiedName());
			wc.setAttribute("org.eclipse.jdt.junit.TESTNAME", methodName != null ? methodName : "");
		} else {
			// Project level
			wc.setAttribute("org.eclipse.jdt.junit.CONTAINER", "=" + projectName);
			wc.setAttribute("org.eclipse.jdt.launching.MAIN_TYPE", "");
			wc.setAttribute("org.eclipse.jdt.junit.TESTNAME", "");
		}

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
				java.util.List.of("/" + projectName));
		wc.setAttribute("org.eclipse.debug.core.MAPPED_RESOURCE_TYPES",
				java.util.List.of("4"));

		return wc.doSave();
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
					var attrs = new java.util.jar.Manifest(is).getMainAttributes();
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

	private IJavaElement getJavaElement(ISelection selection) {
		if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) {
			return null;
		}
		Object element = structured.getFirstElement();
		if (element instanceof IJavaElement je) {
			return je;
		}
		if (element instanceof org.eclipse.core.runtime.IAdaptable adaptable) {
			return adaptable.getAdapter(IJavaElement.class);
		}
		return null;
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
