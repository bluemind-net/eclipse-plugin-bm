package net.bluemind.devtools.testrunner;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "net.bluemind.devtools.testrunner";

	private static Activator plugin;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		PomFileWatcher.instance().start();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		PomFileWatcher.instance().stop();
		plugin = null;
		super.stop(context);
	}

	public static Activator getDefault() {
		return plugin;
	}
}
