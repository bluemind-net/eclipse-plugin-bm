package net.bluemind.devtools.gitchanges.refresh;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;

public class WorkspaceChangeListener implements IResourceChangeListener {

    private final Runnable onChanged;

    public WorkspaceChangeListener(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null) return;
        // Only care about project-level lifecycle changes
        for (IResourceDelta child : delta.getAffectedChildren()) {
            int kind = child.getKind();
            if (kind == IResourceDelta.ADDED
                    || kind == IResourceDelta.REMOVED
                    || kind == IResourceDelta.CHANGED) {
                onChanged.run();
                return;
            }
        }
    }
}
