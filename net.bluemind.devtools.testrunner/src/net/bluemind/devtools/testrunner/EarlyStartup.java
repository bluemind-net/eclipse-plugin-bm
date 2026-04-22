package net.bluemind.devtools.testrunner;

import org.eclipse.ui.IStartup;

/**
 * Forces the bundle to activate at Eclipse startup via the
 * {@code org.eclipse.ui.startup} extension. Without this, the bundle uses its
 * default lazy activation and {@link Activator#start(org.osgi.framework.BundleContext)}
 * only runs the first time another plugin touches this one — which delays (or
 * prevents) the MCP server and POM watcher from starting until the user opens
 * the preferences page or right-clicks a test project.
 */
public class EarlyStartup implements IStartup {

	@Override
	public void earlyStartup() {
		// The call itself forces bundle activation; Activator#start handles the real
		// bootstrap (MCP server, POM watcher). Nothing to do here.
	}
}
