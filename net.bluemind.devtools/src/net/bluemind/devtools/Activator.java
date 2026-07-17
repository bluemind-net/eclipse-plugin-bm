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

public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "net.bluemind.devtools";
	public static final String PREF_MCP_ENABLED = "mcp.enabled";
	public static final String PREF_ICR_INLINE = "icr.inlinePresentation";
	public static final String PREF_POM_WATCH_JRE = "pomWatcher.jre.enabled";
	public static final String PREF_POM_WATCH_TARGET = "pomWatcher.target.enabled";

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
