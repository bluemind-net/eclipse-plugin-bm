package net.bluemind.devtools.icr.ui;

import java.util.Collections;
import java.util.Map;

import org.eclipse.ui.AbstractSourceProvider;
import org.eclipse.ui.ISources;
import org.eclipse.ui.PlatformUI;
import org.eclipse.swt.widgets.Display;

import net.bluemind.devtools.icr.model.IcrSessionStore;

/**
 * Exposes whether an ICR session is active as the workbench expression variable
 * {@code net.bluemind.devtools.icr.active}, so the "Ask Claude (ICR)" menu item
 * only shows while a session is running. Tracks {@link IcrSessionStore}.
 */
public class IcrSourceProvider extends AbstractSourceProvider {

	public static final String VAR_ACTIVE = "net.bluemind.devtools.icr.active";

	private final IcrSessionStore.Listener listener = this::onChanged;

	public IcrSourceProvider() {
		IcrSessionStore.instance().addListener(listener);
	}

	@Override
	public String[] getProvidedSourceNames() {
		return new String[] { VAR_ACTIVE };
	}

	@Override
	public Map<String, Object> getCurrentState() {
		return Collections.singletonMap(VAR_ACTIVE, IcrSessionStore.instance().isActive());
	}

	private void onChanged() {
		Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
		Runnable fire = () -> fireSourceChanged(ISources.WORKBENCH, VAR_ACTIVE,
				IcrSessionStore.instance().isActive());
		if (display == null || display.isDisposed()) {
			fire.run();
		} else {
			display.asyncExec(fire);
		}
	}

	@Override
	public void dispose() {
		IcrSessionStore.instance().removeListener(listener);
	}
}
