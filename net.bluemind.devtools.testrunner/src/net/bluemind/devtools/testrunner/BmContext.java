package net.bluemind.devtools.testrunner;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;

public class BmContext {

	private static final BmContext INSTANCE = new BmContext();

	private final AtomicBoolean dirty = new AtomicBoolean(true);
	private volatile boolean available;
	private IResourceChangeListener resourceListener;

	public static BmContext instance() {
		return INSTANCE;
	}

	public void initialize() {
		refresh();
		resourceListener = this::onResourceChanged;
		ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener,
				IResourceChangeEvent.POST_CHANGE);
	}

	public void dispose() {
		if (resourceListener != null) {
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
			resourceListener = null;
		}
	}

	public boolean isAvailable() {
		if (dirty.compareAndSet(true, false)) {
			refresh();
		}
		return available;
	}

	public boolean hasGlobalPom() {
		return isAvailable();
	}

	private void refresh() {
		PomPropertyReader.clearCache();
		available = PomPropertyReader.findGlobalPom().isPresent();
	}

	private void onResourceChanged(IResourceChangeEvent event) {
		IResourceDelta delta = event.getDelta();
		if (delta == null) {
			return;
		}
		for (IResourceDelta child : delta.getAffectedChildren()) {
			int kind = child.getKind();
			int flags = child.getFlags();
			if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED
					|| (flags & IResourceDelta.OPEN) != 0) {
				dirty.set(true);
				return;
			}
		}
	}
}
