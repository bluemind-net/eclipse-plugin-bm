package net.bluemind.devtools.testrunner;

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "net.bluemind.devtools.testrunner";

	private static Activator plugin;
	private IPropertyChangeListener prefListener;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;

		BmContext.instance().initialize();

		getPreferenceStore().setDefault("codeMining.enabled", false);
		getPreferenceStore().setDefault("pomWatcher.enabled", true);

		if (getPreferenceStore().getBoolean("pomWatcher.enabled")
				&& BmContext.instance().hasGlobalPom()) {
			PomFileWatcher.instance().start();
		}

		prefListener = event -> {
			if ("pomWatcher.enabled".equals(event.getProperty())) {
				boolean enabled = getPreferenceStore().getBoolean("pomWatcher.enabled");
				if (enabled && BmContext.instance().hasGlobalPom()) {
					PomFileWatcher.instance().start();
				} else if (!enabled) {
					PomFileWatcher.instance().stop();
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
		BmContext.instance().dispose();
		plugin = null;
		super.stop(context);
	}

	public static Activator getDefault() {
		return plugin;
	}
}
