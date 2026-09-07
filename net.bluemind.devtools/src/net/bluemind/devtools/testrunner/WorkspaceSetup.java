package net.bluemind.devtools.testrunner;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.launching.AbstractVMInstall;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMStandin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import net.bluemind.devtools.Activator;
import net.bluemind.devtools.testrunner.mcp.BmMcpTools;

/**
 * One-time BlueMind Eclipse workspace bootstrap: license header code template,
 * "organize imports + format" on save, default JDK, and — for a workspace with
 * no project yet (typically a fresh {@code -data} created inside a repo
 * worktree) — project import and working sets. VM arguments and the target
 * platform are handled separately by {@link PomSyncChecker}.
 */
public class WorkspaceSetup {

	private static final ILog LOG = Platform.getLog(WorkspaceSetup.class);

	private static final String JDT_UI_PLUGIN_ID = "org.eclipse.jdt.ui";
	private static final String TEMPLATE_KEY = "org.eclipse.jdt.ui.text.custom_code_templates";
	private static final String NEWTYPE_TEMPLATE_ID = "org.eclipse.jdt.ui.text.codetemplates.newtype";
	private static final String STANDARD_VM_TYPE_ID = "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType";

	private static final String JDT_CORE_PLUGIN_ID = "org.eclipse.jdt.core";
	private static final String COMPILER_COMPLIANCE_KEY = "org.eclipse.jdt.core.compiler.compliance";
	private static final String COMPILER_SOURCE_KEY = "org.eclipse.jdt.core.compiler.source";
	private static final String COMPILER_TARGET_KEY = "org.eclipse.jdt.core.compiler.codegen.targetPlatform";
	private static final String COMPILER_RELEASE_KEY = "org.eclipse.jdt.core.compiler.release";

	private static final String WORKBENCH_PLUGIN_ID = "org.eclipse.ui.workbench";
	private static final String LARGE_VIEW_LIMIT_KEY = "largeViewLimit";

	private static final String MPC_UI_PLUGIN_ID = "org.eclipse.epp.mpc.ui";
	private static final String MPC_NATURE_LOOKUP_KEY = "org.eclipse.epp.mpc.naturelookup";

	private static final String NEWTYPE_TEMPLATE_BODY = "/* BEGIN LICENSE\n"
			+ "  * Copyright © Blue Mind SAS, 2012-${year}\n"
			+ "  *\n"
			+ "  * This file is part of BlueMind. BlueMind is a messaging and collaborative\n"
			+ "  * solution.\n"
			+ "  *\n"
			+ "  * This program is free software; you can redistribute it and/or modify\n"
			+ "  * it under the terms of either the GNU Affero General Public License as\n"
			+ "  * published by the Free Software Foundation (version 3 of the License).\n"
			+ "  *\n"
			+ "  * This program is distributed in the hope that it will be useful,\n"
			+ "  * but WITHOUT ANY WARRANTY; without even the implied warranty of\n"
			+ "  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.\n"
			+ "  *\n"
			+ "  * See LICENSE.txt\n"
			+ "  * END LICENSE\n"
			+ "  */\n"
			+ "${filecomment}\n"
			+ "${package_declaration}\n"
			+ "\n"
			+ "${typecomment}\n"
			+ "${type_declaration}";

	/**
	 * "Organize imports" + "format" on save — the {@code sp_cleanup.*} cleanup
	 * profile plus the save-participant switch, captured from a reference
	 * BlueMind workspace so every dev shares the exact same save behavior.
	 */
	private static final Properties SAVE_ACTIONS = loadSaveActions();

	private WorkspaceSetup() {
	}

	/**
	 * The first time a BlueMind workspace is detected, asks once whether to set
	 * it up. Declining is remembered (like accepting) — it won't ask again; use
	 * "BlueMind > Setup Eclipse Workspace..." to run it later on demand.
	 */
	public static void runIfNeeded() {
		if (Activator.getDefault().getPreferenceStore().getBoolean(Activator.PREF_WORKSPACE_SETUP_DONE)) {
			return;
		}
		Path repoRoot = bootstrapRepoRoot();
		if (repoRoot == null) {
			return;
		}
		Display.getDefault().asyncExec(() -> confirmAndRunIfNeeded(repoRoot));
	}

	private static void confirmAndRunIfNeeded(Path repoRoot) {
		boolean empty = ResourcesPlugin.getWorkspace().getRoot().getProjects().length == 0;
		String message = "Set up this Eclipse workspace for BlueMind development?\n\n"
				+ (empty ? "This will import the projects found in " + repoRoot
						+ ", organize working sets, and configure "
						: "This will configure ")
				+ "the license header, save actions, JDK, compiler settings and target platform.";
		if (MessageDialog.openQuestion(activeShell(), "BlueMind Workspace Setup", message)) {
			schedule(repoRoot, true);
		} else {
			Activator.getDefault().getPreferenceStore().setValue(Activator.PREF_WORKSPACE_SETUP_DONE, true);
		}
	}

	/** Re-runs everything on demand — the "BlueMind > Setup Eclipse Workspace..." command. */
	public static void runManual() {
		Path repoRoot = bootstrapRepoRoot();
		if (repoRoot == null) {
			Display.getDefault().asyncExec(() -> {
				Shell shell = activeShell();
				if (shell != null) {
					MessageDialog.openInformation(shell, "BlueMind Workspace Setup",
							"No BlueMind repository found. Open a project from a BlueMind checkout first, "
									+ "or create the workspace inside a repo worktree.");
				}
			});
			return;
		}
		schedule(repoRoot, true);
	}

	private static Path bootstrapRepoRoot() {
		return PomPropertyReader.findRepoRoot().or(PomPropertyReader::findRepoRootFromWorkspaceLocation)
				.orElse(null);
	}

	private static void schedule(Path repoRoot, boolean interactive) {
		Job job = new Job("Setting up BlueMind Eclipse workspace") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				StringJoiner summary = new StringJoiner("\n");
				// Disabled first, before any project is touched: the Marketplace nature
				// detector reacts to the resource-change events importAndOrganize() is
				// about to fire and can pop its dialog within seconds of the first
				// import — well before this job would otherwise get around to disabling
				// it, since importing ~1400 projects takes a minute or more.
				// Both run on the UI thread: their property-change listeners
				// (WorkbenchViewerSetup, MissingNatureDetector) touch SWT widgets
				// directly and would throw "Invalid thread access" from this Job.
				if (runOnUiThread(WorkspaceSetup::applyDisableViewPagination)) {
					summary.add("Disabled the \"show remaining items\" limit in tree/table views.");
				}
				if (runOnUiThread(WorkspaceSetup::applyDisableMarketplaceSolutions)) {
					summary.add("Disabled the Marketplace solution popup for missing project natures.");
				}
				if (ResourcesPlugin.getWorkspace().getRoot().getProjects().length == 0) {
					importAndOrganize(repoRoot, summary);
				}
				if (applyLicenseHeader()) {
					summary.add("License header template configured.");
				}
				if (applySaveActions()) {
					summary.add("Organize imports + format on save configured.");
				}
				applyJdk(summary);
				applyPomSync(summary);

				Activator.getDefault().getPreferenceStore().setValue(Activator.PREF_WORKSPACE_SETUP_DONE, true);
				LOG.info("Workspace setup done for " + repoRoot + ": "
						+ (summary.length() == 0 ? "nothing to change" : summary.toString().replace('\n', ' ')));

				if (interactive) {
					String text = summary.length() == 0 ? "Nothing to change — already up to date."
							: summary.toString();
					Display.getDefault().asyncExec(() -> {
						Shell shell = activeShell();
						if (shell != null) {
							MessageDialog.openInformation(shell, "BlueMind Workspace Setup", text);
						}
					});
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(interactive);
		job.setSystem(!interactive);
		job.schedule();
	}

	/**
	 * Imports and organizes projects on a workspace that has none yet — the
	 * bootstrap case. Runs unattended: the workspace-mutation consent (Window >
	 * Preferences > BlueMind) would otherwise pop up a dialog for a background
	 * job nobody is watching, so a still-default "ask" is elevated to "always"
	 * for the duration of this call only; an explicit "never" is left as is.
	 */
	private static void importAndOrganize(Path repoRoot, StringJoiner summary) {
		var store = Activator.getDefault().getPreferenceStore();
		String savedProjectsConsent = store.getString(Activator.PREF_CONSENT_PROJECTS);
		String savedWorkingSetsConsent = store.getString(Activator.PREF_CONSENT_WORKINGSETS);
		if ("ask".equals(savedProjectsConsent)) {
			store.setValue(Activator.PREF_CONSENT_PROJECTS, "always");
		}
		if ("ask".equals(savedWorkingSetsConsent)) {
			store.setValue(Activator.PREF_CONSENT_WORKINGSETS, "always");
		}
		try {
			// Not necessarily importResult.ok(): syncProjects also flags pending compile
			// errors as an "issue", which is the expected state right after import —
			// nothing has a target platform yet. applyPomSync() (called after this)
			// fixes that; a genuine import failure would still show up in this summary.
			var importResult = BmMcpTools.syncProjects(repoRoot.toString(), true, true);
			summary.add("Projects imported from " + repoRoot + " — " + firstLine(importResult.markdown()));
			var workingSetsResult = BmMcpTools.syncWorkingSets(repoRoot.toString(), true, false, null);
			summary.add("Working sets: " + (workingSetsResult.ok() ? "organized"
					: "organize failed — " + firstLine(workingSetsResult.markdown())));
		} finally {
			store.setValue(Activator.PREF_CONSENT_PROJECTS, savedProjectsConsent);
			store.setValue(Activator.PREF_CONSENT_WORKINGSETS, savedWorkingSetsConsent);
		}
	}

	private static boolean applyLicenseHeader() {
		Preferences node = InstanceScope.INSTANCE.getNode(JDT_UI_PLUGIN_ID);
		String existingXml = node.get(TEMPLATE_KEY, "");
		if (existingXml.contains(NEWTYPE_TEMPLATE_BODY)) {
			return false;
		}
		try {
			node.put(TEMPLATE_KEY, mergeNewtypeTemplate(existingXml));
			node.flush();
			return true;
		} catch (Exception e) {
			LOG.error("Failed to set the license header code template", e);
			return false;
		}
	}

	private static String mergeNewtypeTemplate(String existingXml) throws Exception {
		var dbf = DocumentBuilderFactory.newInstance();
		Document doc;
		if (existingXml != null && !existingXml.isBlank()) {
			doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(existingXml)));
		} else {
			doc = dbf.newDocumentBuilder().newDocument();
			doc.appendChild(doc.createElement("templates"));
		}
		Element root = doc.getDocumentElement();

		Element target = null;
		NodeList templates = root.getElementsByTagName("template");
		for (int i = 0; i < templates.getLength(); i++) {
			Element el = (Element) templates.item(i);
			if (NEWTYPE_TEMPLATE_ID.equals(el.getAttribute("id"))) {
				target = el;
				break;
			}
		}
		if (target == null) {
			target = doc.createElement("template");
			target.setAttribute("id", NEWTYPE_TEMPLATE_ID);
			target.setAttribute("name", "newtype");
			target.setAttribute("description", "Newly created files");
			target.setAttribute("context", "newtype_context");
			root.appendChild(target);
		}
		target.setAttribute("autoinsert", "false");
		target.setAttribute("deleted", "false");
		target.setAttribute("enabled", "true");
		while (target.hasChildNodes()) {
			target.removeChild(target.getFirstChild());
		}
		target.appendChild(doc.createTextNode(NEWTYPE_TEMPLATE_BODY));

		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		StringWriter sw = new StringWriter();
		transformer.transform(new DOMSource(doc), new StreamResult(sw));
		return sw.toString();
	}

	private static boolean applySaveActions() {
		Preferences node = InstanceScope.INSTANCE.getNode(JDT_UI_PLUGIN_ID);
		boolean changed = false;
		for (String key : SAVE_ACTIONS.stringPropertyNames()) {
			String value = SAVE_ACTIONS.getProperty(key);
			if (!value.equals(node.get(key, null))) {
				node.put(key, value);
				changed = true;
			}
		}
		if (changed) {
			try {
				node.flush();
			} catch (BackingStoreException e) {
				LOG.error("Failed to save the save-actions cleanup profile", e);
			}
		}
		return changed;
	}

	/**
	 * Disables the "Show remaining N items" pagination that large tree/table
	 * views (Package Explorer included) apply past a threshold — {@code 0}
	 * (or negative) tells {@code ColumnViewer} not to limit at all.
	 */
	private static boolean applyDisableViewPagination() {
		Preferences node = InstanceScope.INSTANCE.getNode(WORKBENCH_PLUGIN_ID);
		if (node.getInt(LARGE_VIEW_LIMIT_KEY, -1) == 0) {
			return false;
		}
		node.putInt(LARGE_VIEW_LIMIT_KEY, 0);
		try {
			node.flush();
		} catch (BackingStoreException e) {
			LOG.error("Failed to disable the large-view item limit", e);
		}
		return true;
	}

	/** Disables the Marketplace Client's "a solution may be available" popup for missing project natures. */
	private static boolean applyDisableMarketplaceSolutions() {
		Preferences node = InstanceScope.INSTANCE.getNode(MPC_UI_PLUGIN_ID);
		if (!node.getBoolean(MPC_NATURE_LOOKUP_KEY, true)) {
			return false;
		}
		node.putBoolean(MPC_NATURE_LOOKUP_KEY, false);
		try {
			node.flush();
		} catch (BackingStoreException e) {
			LOG.error("Failed to disable Marketplace solution lookups", e);
		}
		return true;
	}

	/**
	 * Sets the workspace-default Java compiler compliance (source/target/release)
	 * to the version required by the POM. Without this, a project that has no
	 * {@code .settings/org.eclipse.jdt.core.prefs} of its own — and relies on the
	 * workspace default, like most BlueMind bundles — gets compiled against
	 * whatever Eclipse ships with by default, not what the project actually needs
	 * (e.g. rejecting a legitimate pre-Java-9 {@code _} identifier).
	 */
	private static boolean applyCompilerCompliance(String requiredVersion) {
		Preferences node = InstanceScope.INSTANCE.getNode(JDT_CORE_PLUGIN_ID);
		if (requiredVersion.equals(node.get(COMPILER_COMPLIANCE_KEY, null))) {
			return false;
		}
		node.put(COMPILER_COMPLIANCE_KEY, requiredVersion);
		node.put(COMPILER_SOURCE_KEY, requiredVersion);
		node.put(COMPILER_TARGET_KEY, requiredVersion);
		node.put(COMPILER_RELEASE_KEY, "enabled");
		try {
			node.flush();
		} catch (BackingStoreException e) {
			LOG.error("Failed to set the workspace compiler compliance level", e);
		}
		return true;
	}

	private static void applyJdk(StringJoiner summary) {
		var pomProps = PomPropertyReader.findGlobalPom().flatMap(PomPropertyReader::readProperties).orElse(null);
		if (pomProps == null || pomProps.requiredJavaVersion() == null) {
			return;
		}
		String requiredVersion = pomProps.requiredJavaVersion();

		// Same reasoning as applyDisableViewPagination/applyDisableMarketplaceSolutions:
		// JDT's compiler-compliance preference change triggers listeners that touch
		// SWT (marker/build state refresh), so this must run on the UI thread too.
		if (runOnUiThread(() -> applyCompilerCompliance(requiredVersion))) {
			summary.add("Workspace compiler compliance set to " + requiredVersion + ".");
		}

		IVMInstall current = JavaRuntime.getDefaultVMInstall();
		if (current != null && matchesVersion(current, requiredVersion)) {
			return;
		}

		IVMInstall match = findInstalledVm(requiredVersion);
		if (match == null) {
			match = registerVmFromDisk(requiredVersion);
		}
		if (match == null) {
			summary.add("JDK: no Java " + requiredVersion + " found on this machine.");
			Display.getDefault().asyncExec(() -> {
				Shell shell = activeShell();
				if (shell != null) {
					MessageDialog.openWarning(shell, "BlueMind Workspace Setup",
							"This BlueMind checkout requires Java " + requiredVersion
									+ ", but no matching JDK was found on this machine.\n\nInstall one, e.g.:\n"
									+ "  sudo apt install openjdk-" + requiredVersion + "-jdk\n\n"
									+ "then run BlueMind → Setup Eclipse Workspace... again.");
				}
			});
			return;
		}

		try {
			JavaRuntime.setDefaultVMInstall(match, new NullProgressMonitor());
			summary.add("JDK: default VM set to " + match.getName());
		} catch (CoreException e) {
			LOG.error("Failed to set the default JDK", e);
		}
	}

	/**
	 * Syncs JVM arguments and the target platform from the global POM — same
	 * logic as {@link PomSyncChecker}'s "Check POM Sync..." command, applied
	 * unattended instead of behind its confirmation dialog. Without this, a
	 * freshly-imported workspace has no target platform at all and every
	 * project shows unresolved-dependency errors.
	 */
	private static void applyPomSync(StringJoiner summary) {
		var status = PomSyncChecker.computeStatus();
		if (status == null || !status.hasMismatch()) {
			return;
		}
		boolean hadTarget = status.workspaceConfig().hasTarget();
		PomSyncChecker.applySync(status);
		if (status.vmArgsMismatch()) {
			summary.add("JVM arguments synced from POM.");
		}
		if (status.targetPlatformMismatch()) {
			summary.add("Target platform " + (hadTarget ? "updated" : "created") + " from POM (loading in background).");
		}
	}

	private static boolean matchesVersion(IVMInstall vm, String requiredVersion) {
		if (vm instanceof AbstractVMInstall avi) {
			String version = avi.getJavaVersion();
			if (version != null) {
				String major = version.contains(".") ? version.substring(0, version.indexOf('.')) : version;
				if (requiredVersion.equals(major)) {
					return true;
				}
			}
		}
		File location = vm.getInstallLocation();
		return location != null && location.getName().matches("(java|jdk)-?" + requiredVersion + "([.\\-].*)?");
	}

	private static IVMInstall findInstalledVm(String requiredVersion) {
		for (IVMInstallType type : JavaRuntime.getVMInstallTypes()) {
			for (IVMInstall vm : type.getVMInstalls()) {
				if (matchesVersion(vm, requiredVersion)) {
					return vm;
				}
			}
		}
		return null;
	}

	/** Registers a JDK found under the standard Debian/Ubuntu {@code /usr/lib/jvm} layout. */
	private static IVMInstall registerVmFromDisk(String requiredVersion) {
		File jvmDir = new File("/usr/lib/jvm");
		File[] candidates = jvmDir.listFiles((dir, name) -> name.matches("java-" + requiredVersion + "-openjdk.*"));
		if (candidates == null || candidates.length == 0) {
			return null;
		}
		File installLocation = candidates[0];

		IVMInstallType type = JavaRuntime.getVMInstallType(STANDARD_VM_TYPE_ID);
		if (type == null) {
			return null;
		}
		String id = "bluemind-java-" + requiredVersion;
		IVMInstall existing = type.findVMInstall(id);
		if (existing != null) {
			return existing;
		}
		VMStandin standin = new VMStandin(type, id);
		standin.setName(installLocation.getName());
		standin.setInstallLocation(installLocation);
		return standin.convertToRealVM();
	}

	private static Properties loadSaveActions() {
		Properties props = new Properties();
		try (StringReader reader = new StringReader(SAVE_ACTIONS_SOURCE)) {
			props.load(reader);
		} catch (Exception e) {
			LOG.error("Failed to load the built-in save-actions cleanup profile", e);
		}
		return props;
	}

	private static String firstLine(String markdown) {
		int nl = markdown.indexOf('\n');
		String line = nl < 0 ? markdown : markdown.substring(0, nl);
		return line.replaceFirst("^#\\s*", "").trim();
	}

	private static Shell activeShell() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		return window != null ? window.getShell() : null;
	}

	/**
	 * Runs {@code action} on the UI thread and returns its result. Needed for
	 * preference writes whose property-change listeners touch SWT widgets
	 * directly (e.g. {@code WorkbenchViewerSetup} refreshing registered
	 * viewers) — calling those from this job's own background thread throws
	 * "Invalid thread access".
	 */
	private static boolean runOnUiThread(BooleanSupplier action) {
		Display display = Display.getDefault();
		if (display.getThread() == Thread.currentThread()) {
			// syncExec from the UI thread itself would deadlock; callers only ever
			// reach this from the bootstrap Job's background thread today, but guard
			// against it defensively rather than relying on that staying true.
			return action.getAsBoolean();
		}
		boolean[] result = { false };
		display.syncExec(() -> result[0] = action.getAsBoolean());
		return result[0];
	}

	private static final String SAVE_ACTIONS_SOURCE = """
			editor_save_participant_org.eclipse.jdt.ui.postsavelistener.cleanup=true
			sp_cleanup.add_all=false
			sp_cleanup.add_default_serial_version_id=true
			sp_cleanup.add_generated_serial_version_id=false
			sp_cleanup.add_missing_annotations=true
			sp_cleanup.add_missing_deprecated_annotations=true
			sp_cleanup.add_missing_methods=false
			sp_cleanup.add_missing_nls_tags=false
			sp_cleanup.add_missing_override_annotations=true
			sp_cleanup.add_missing_override_annotations_interface_methods=true
			sp_cleanup.add_serial_version_id=false
			sp_cleanup.also_simplify_lambda=true
			sp_cleanup.always_use_blocks=true
			sp_cleanup.always_use_parentheses_in_expressions=false
			sp_cleanup.always_use_this_for_non_static_field_access=false
			sp_cleanup.always_use_this_for_non_static_method_access=false
			sp_cleanup.array_with_curly=false
			sp_cleanup.arrays_fill=false
			sp_cleanup.bitwise_conditional_expression=false
			sp_cleanup.boolean_literal=false
			sp_cleanup.boolean_value_rather_than_comparison=false
			sp_cleanup.break_loop=false
			sp_cleanup.collection_cloning=false
			sp_cleanup.comparing_on_criteria=false
			sp_cleanup.comparison_statement=false
			sp_cleanup.controlflow_merge=false
			sp_cleanup.convert_functional_interfaces=false
			sp_cleanup.convert_to_enhanced_for_loop=false
			sp_cleanup.convert_to_enhanced_for_loop_if_loop_var_used=false
			sp_cleanup.convert_to_switch_expressions=false
			sp_cleanup.correct_indentation=false
			sp_cleanup.do_while_rather_than_while=false
			sp_cleanup.double_negation=false
			sp_cleanup.else_if=false
			sp_cleanup.embedded_if=false
			sp_cleanup.evaluate_nullable=false
			sp_cleanup.extract_increment=false
			sp_cleanup.format_source_code=true
			sp_cleanup.format_source_code_changes_only=false
			sp_cleanup.hash=false
			sp_cleanup.if_condition=false
			sp_cleanup.insert_inferred_type_arguments=false
			sp_cleanup.instanceof=false
			sp_cleanup.instanceof_keyword=false
			sp_cleanup.invert_equals=false
			sp_cleanup.join=false
			sp_cleanup.lazy_logical_operator=false
			sp_cleanup.make_local_variable_final=true
			sp_cleanup.make_parameters_final=false
			sp_cleanup.make_private_fields_final=true
			sp_cleanup.make_type_abstract_if_missing_method=false
			sp_cleanup.make_variable_declarations_final=false
			sp_cleanup.map_cloning=false
			sp_cleanup.merge_conditional_blocks=false
			sp_cleanup.module_imports=false
			sp_cleanup.multi_catch=false
			sp_cleanup.never_use_blocks=false
			sp_cleanup.never_use_parentheses_in_expressions=true
			sp_cleanup.no_string_creation=false
			sp_cleanup.no_super=false
			sp_cleanup.number_suffix=false
			sp_cleanup.objects_equals=false
			sp_cleanup.on_save_use_additional_actions=false
			sp_cleanup.one_if_rather_than_duplicate_blocks_that_fall_through=false
			sp_cleanup.operand_factorization=false
			sp_cleanup.organize_imports=true
			sp_cleanup.overridden_assignment=false
			sp_cleanup.overridden_assignment_move_decl=true
			sp_cleanup.plain_replacement=false
			sp_cleanup.precompile_regex=false
			sp_cleanup.primitive_comparison=false
			sp_cleanup.primitive_parsing=false
			sp_cleanup.primitive_rather_than_wrapper=false
			sp_cleanup.primitive_serialization=false
			sp_cleanup.pull_out_if_from_if_else=false
			sp_cleanup.pull_up_assignment=false
			sp_cleanup.push_down_negation=false
			sp_cleanup.qualify_static_field_accesses_with_declaring_class=false
			sp_cleanup.qualify_static_member_accesses_through_instances_with_declaring_class=true
			sp_cleanup.qualify_static_member_accesses_through_subtypes_with_declaring_class=true
			sp_cleanup.qualify_static_member_accesses_with_declaring_class=false
			sp_cleanup.qualify_static_method_accesses_with_declaring_class=false
			sp_cleanup.reduce_indentation=false
			sp_cleanup.redundant_comparator=false
			sp_cleanup.redundant_falling_through_block_end=false
			sp_cleanup.remove_private_constructors=true
			sp_cleanup.remove_redundant_modifiers=false
			sp_cleanup.remove_redundant_semicolons=false
			sp_cleanup.remove_redundant_type_arguments=false
			sp_cleanup.remove_trailing_whitespaces=false
			sp_cleanup.remove_trailing_whitespaces_all=true
			sp_cleanup.remove_trailing_whitespaces_ignore_empty=false
			sp_cleanup.remove_unnecessary_array_creation=false
			sp_cleanup.remove_unnecessary_casts=true
			sp_cleanup.remove_unnecessary_nls_tags=false
			sp_cleanup.remove_unnecessary_suppress_warnings=false
			sp_cleanup.remove_unused_imports=false
			sp_cleanup.remove_unused_local_variables=false
			sp_cleanup.remove_unused_method_parameters=false
			sp_cleanup.remove_unused_private_fields=true
			sp_cleanup.remove_unused_private_members=false
			sp_cleanup.remove_unused_private_methods=true
			sp_cleanup.remove_unused_private_types=true
			sp_cleanup.replace_deprecated_calls=false
			sp_cleanup.replace_deprecated_fields=false
			sp_cleanup.return_expression=false
			sp_cleanup.simplify_boolean_if_else=false
			sp_cleanup.simplify_lambda_expression_and_method_ref=false
			sp_cleanup.single_used_field=false
			sp_cleanup.sort_members=false
			sp_cleanup.sort_members_all=false
			sp_cleanup.standard_comparison=false
			sp_cleanup.static_inner_class=false
			sp_cleanup.strictly_equal_or_different=false
			sp_cleanup.stringbuffer_to_stringbuilder=false
			sp_cleanup.stringbuilder=false
			sp_cleanup.stringbuilder_for_local_vars=true
			sp_cleanup.stringconcat_stringbuffer_stringbuilder=false
			sp_cleanup.stringconcat_to_textblock=false
			sp_cleanup.substring=false
			sp_cleanup.switch=false
			sp_cleanup.switch_for_instanceof_pattern=false
			sp_cleanup.system_property=false
			sp_cleanup.system_property_boolean=false
			sp_cleanup.system_property_file_encoding=false
			sp_cleanup.system_property_file_separator=false
			sp_cleanup.system_property_javaspecversion=false
			sp_cleanup.system_property_javaversion=false
			sp_cleanup.system_property_line_separator=false
			sp_cleanup.system_property_path_separator=false
			sp_cleanup.ternary_operator=false
			sp_cleanup.try_with_resource=false
			sp_cleanup.unlooped_while=false
			sp_cleanup.unreachable_block=false
			sp_cleanup.use_anonymous_class_creation=false
			sp_cleanup.use_autoboxing=false
			sp_cleanup.use_blocks=true
			sp_cleanup.use_blocks_only_for_return_and_throw=false
			sp_cleanup.use_directly_map_method=false
			sp_cleanup.use_lambda=true
			sp_cleanup.use_parentheses_in_expressions=false
			sp_cleanup.use_string_is_blank=false
			sp_cleanup.use_this_for_non_static_field_access=false
			sp_cleanup.use_this_for_non_static_field_access_only_if_necessary=true
			sp_cleanup.use_this_for_non_static_method_access=false
			sp_cleanup.use_this_for_non_static_method_access_only_if_necessary=true
			sp_cleanup.use_unboxing=false
			sp_cleanup.use_var=false
			sp_cleanup.useless_continue=false
			sp_cleanup.useless_return=false
			sp_cleanup.valueof_rather_than_instantiation=false
			""";
}
