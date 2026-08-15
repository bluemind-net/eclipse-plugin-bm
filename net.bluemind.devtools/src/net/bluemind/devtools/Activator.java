package net.bluemind.devtools;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import net.bluemind.devtools.icr.model.IcrSessionStore;
import net.bluemind.devtools.icr.ui.IcrEditors;
import net.bluemind.devtools.testrunner.BmContext;
import net.bluemind.devtools.testrunner.PomFileWatcher;
import net.bluemind.devtools.testrunner.mcp.BmMcpConfigFile;
import net.bluemind.devtools.testrunner.mcp.BmMcpServer;
import net.bluemind.devtools.testrunner.mcp.BmMcpTools;

public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "net.bluemind.devtools";
	public static final String PREF_MCP_ENABLED = "mcp.enabled";
	public static final String PREF_ICR_INLINE = "icr.inlinePresentation";
	public static final String PREF_POM_WATCH_JRE = "pomWatcher.jre.enabled";
	public static final String PREF_POM_WATCH_TARGET = "pomWatcher.target.enabled";
	/** "ask" (default) / "always" / "never" — guards import/removal of workspace projects. */
	public static final String PREF_CONSENT_PROJECTS = "workspace.consent.projects";
	/** "ask" (default) / "always" / "never" — guards creation/update/removal of working sets. */
	public static final String PREF_CONSENT_WORKINGSETS = "workspace.consent.workingsets";
	/** Newline-separated names of working sets created and managed by sync_working_sets. */
	public static final String PREF_WORKINGSETS_MANAGED = "workspace.workingsets.managed";
	/**
	 * Auto-build state saved by apply_workspace_batch while auto-build is suspended
	 * ("" = nothing suspended, "true"/"false" = value to restore). Persisted so a
	 * crash mid-batch can be recovered at the next start(), never leaving the
	 * workspace with auto-build silently off.
	 */
	public static final String PREF_AUTOBUILD_SAVED = "workspace.autobuild.saved";

	/** Status icons used by the Branch Changed Files view, preloaded into the image registry. */
	private static final String[] STATUS_ICONS = { "modified", "added", "deleted", "renamed", "copied" };

	private static final ILog LOG = Platform.getLog(Activator.class);

	private static Activator plugin;
	private IPropertyChangeListener prefListener;
	private BmMcpServer mcpServer;

	/** Refreshes editor code minings whenever the ICR model changes. */
	private final IcrSessionStore.Listener icrListener = IcrEditors::refreshCodeMinings;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;

		preloadStatusIcons();

		BmContext.instance().initialize();

		IcrSessionStore.instance().addListener(icrListener);

		getPreferenceStore().setDefault("codeMining.enabled", false);
		getPreferenceStore().setDefault(PREF_POM_WATCH_JRE, true);
		getPreferenceStore().setDefault(PREF_POM_WATCH_TARGET, true);
		getPreferenceStore().setDefault(PREF_MCP_ENABLED, true);
		getPreferenceStore().setDefault(PREF_ICR_INLINE, true);
		getPreferenceStore().setDefault(PREF_CONSENT_PROJECTS, "ask");
		getPreferenceStore().setDefault(PREF_CONSENT_WORKINGSETS, "ask");
		getPreferenceStore().setDefault(PREF_WORKINGSETS_MANAGED, "");
		getPreferenceStore().setDefault(PREF_AUTOBUILD_SAVED, "");

		// Recover from a batch that suspended auto-build and never restored it
		// (e.g. a hard crash between suspend and the finally block last session).
		BmMcpTools.restoreSuspendedAutoBuild();

		if (isPomWatchEnabled() && BmContext.instance().hasGlobalPom()) {
			PomFileWatcher.instance().start();
		}

		if (getPreferenceStore().getBoolean(PREF_MCP_ENABLED)) {
			startMcpServer();
		}

		prefListener = event -> {
			if (PREF_POM_WATCH_JRE.equals(event.getProperty())
					|| PREF_POM_WATCH_TARGET.equals(event.getProperty())) {
				if (isPomWatchEnabled() && BmContext.instance().hasGlobalPom()) {
					PomFileWatcher.instance().start();
				} else if (!isPomWatchEnabled()) {
					PomFileWatcher.instance().stop();
				}
			} else if (PREF_MCP_ENABLED.equals(event.getProperty())) {
				boolean enabled = getPreferenceStore().getBoolean(PREF_MCP_ENABLED);
				if (enabled) {
					startMcpServer();
				} else {
					stopMcpServer();
				}
			}
		};
		getPreferenceStore().addPropertyChangeListener(prefListener);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		if (prefListener != null) {
			getPreferenceStore().removePropertyChangeListener(prefListener);
			prefListener = null;
		}
		// Last-resort net: if a batch is still holding auto-build suspended, restore it.
		BmMcpTools.restoreSuspendedAutoBuild();
		// Same idea for the doctor's informative job: never leave one behind.
		BmMcpTools.stopDoctorStatus();
		PomFileWatcher.instance().stop();
		stopMcpServer();
		IcrSessionStore.instance().removeListener(icrListener);
		IcrSessionStore.instance().dispose();
		BmContext.instance().dispose();
		plugin = null;
		super.stop(context);
	}

	public static Activator getDefault() {
		return plugin;
	}

	public synchronized BmMcpServer getMcpServer() {
		return mcpServer;
	}

	/** The POM watcher runs when either the JRE-args or target-platform sync is enabled. */
	public boolean isPomWatchEnabled() {
		return getPreferenceStore().getBoolean(PREF_POM_WATCH_JRE)
				|| getPreferenceStore().getBoolean(PREF_POM_WATCH_TARGET);
	}

	private void preloadStatusIcons() {
		for (String name : STATUS_ICONS) {
			String path = "icons/" + name + ".png";
			getImageRegistry().put(path, imageDescriptorFromPlugin(PLUGIN_ID, path));
		}
	}

	private synchronized void startMcpServer() {
		if (mcpServer != null && mcpServer.isRunning()) {
			return;
		}
		try {
			mcpServer = new BmMcpServer();
			mcpServer.start();
			BmMcpConfigFile.write(mcpServer);
		} catch (Exception e) {
			LOG.error("Failed to start MCP server: " + e.getMessage(), e);
			mcpServer = null;
		}
	}

	private synchronized void stopMcpServer() {
		if (mcpServer != null) {
			mcpServer.stop();
			mcpServer = null;
		}
		BmMcpConfigFile.delete();
	}
}
