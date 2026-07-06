package net.bluemind.devtools.gitchanges.refresh;

import org.eclipse.egit.core.internal.indexdiff.IndexDiffChangedListener;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffData;

public class IndexDiffListener implements IndexDiffChangedListener {

    private final Runnable onChanged;

    public IndexDiffListener(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    @Override
    public void indexDiffChanged(org.eclipse.jgit.lib.Repository repo, IndexDiffData indexDiffData) {
        onChanged.run();
    }
}
