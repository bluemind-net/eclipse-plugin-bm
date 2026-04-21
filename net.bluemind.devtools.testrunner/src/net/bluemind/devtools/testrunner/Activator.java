package net.bluemind.devtools.testrunner;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import net.bluemind.devtools.testrunner.mcp.BmMcpConfigFile;
import net.bluemind.devtools.testrunner.mcp.BmMcpServer;

public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "net.bluemind.devtools.testrunner";
	public static final String PREF_MCP_ENABLED = "mcp.enabled";

	private static final ILog LOG = Platform.getLog(Activator.class);

	private static Activator plugin;
	private IPropertyChangeListener prefListener;
	private BmMcpServer mcpServer;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;

		BmContext.instance().initialize();

		getPreferenceStore().setDefault("codeMining.enabled", false);
		getPreferenceStore().setDefault("pomWatcher.enabled", true);
		getPreferenceStore().setDefault(PREF_MCP_ENABLED, true);

		if (getPreferenceStore().getBoolean("pomWatcher.enabled")
				&& BmContext.instance().hasGlobalPom()) {
			PomFileWatcher.instance().start();
		}

		if (getPreferenceStore().getBoolean(PREF_MCP_ENABLED)) {
			startMcpServer();
		}

		prefListener = event -> {
			if ("pomWatcher.enabled".equals(event.getProperty())) {
				boolean enabled = getPreferenceStore().getBoolean("pomWatcher.enabled");
				if (enabled && BmContext.instance().hasGlobalPom()) {
					PomFileWatcher.instance().start();
				} else if (!enabled) {
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
