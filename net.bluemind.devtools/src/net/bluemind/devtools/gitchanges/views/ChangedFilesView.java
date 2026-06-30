package net.bluemind.devtools.gitchanges.views;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCache;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCacheEntry;
import org.eclipse.egit.core.internal.storage.GitFileRevision;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.team.core.history.IFileRevision;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.ITypedElement;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;

import net.bluemind.devtools.Activator;
import net.bluemind.devtools.gitchanges.git.MergeBaseDiffComputer;
import net.bluemind.devtools.gitchanges.quickdiff.QuickDiffBaselineManager;
import net.bluemind.devtools.gitchanges.model.ChangedFile;
import net.bluemind.devtools.gitchanges.model.FileStatus;
import net.bluemind.devtools.gitchanges.refresh.IndexDiffListener;
import net.bluemind.devtools.gitchanges.refresh.WorkspaceChangeListener;

public class ChangedFilesView extends ViewPart {

    public static final String ID = "net.bluemind.devtools.gitchanges.views.changedFiles";

    private TableViewer viewer;
    private Label statusLabel;

    private WorkspaceChangeListener workspaceListener;
    // Fix: one listener per repo, keyed by repository
    private final Map<Repository, IndexDiffListener> indexListeners = new HashMap<>();
    private final Map<Repository, IndexDiffCacheEntry> cacheEntries = new HashMap<>();

    private final ScheduledRefreshJob refreshJob = new ScheduledRefreshJob();

    /** User-specified target branch, or null to auto-detect. */
    private String targetBranch = null;

    /** Drives the editor QuickDiff change ruler from the selected reference branch. */
    private final QuickDiffBaselineManager baselineManager = new QuickDiffBaselineManager();
    /** True once the user has explicitly selected a reference branch; keeps the ruler in sync. */
    private boolean quickDiffActive = false;
    /** Applies the git QuickDiff ruler to text editors opened after activation. */
    private IPartListener2 quickDiffPartListener;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        viewer = new TableViewer(parent,
            SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumns();

        viewer.setContentProvider(new IStructuredContentProvider() {
            @Override
            @SuppressWarnings("unchecked")
            public Object[] getElements(Object input) {
                if (input instanceof List) return ((List<?>) input).toArray();
                return new Object[0];
            }
        });

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("Loading...");

        viewer.addDoubleClickListener(event -> openFile());
        createContextMenu();

        registerListeners();
        addToolbarActions();
        triggerRefresh();
    }

    private void createColumns() {
        TableViewerColumn statusCol = new TableViewerColumn(viewer, SWT.NONE);
        statusCol.getColumn().setText("Status");
        statusCol.getColumn().setWidth(90);
        statusCol.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object element) {
                return ((ChangedFile) element).status.name();
            }
            @Override public Image getImage(Object element) {
                return getStatusImage(((ChangedFile) element).status);
            }
        });

        TableViewerColumn nameCol = new TableViewerColumn(viewer, SWT.NONE);
        nameCol.getColumn().setText("File");
        nameCol.getColumn().setWidth(200);
        nameCol.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object element) {
                return ((ChangedFile) element).fileName;
            }
        });

        TableViewerColumn pathCol = new TableViewerColumn(viewer, SWT.NONE);
        pathCol.getColumn().setText("Path");
        pathCol.getColumn().setWidth(350);
        pathCol.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object element) {
                ChangedFile f = (ChangedFile) element;
                return f.oldPath != null
                    ? f.parentPath + "  (was: " + f.oldPath + ")"
                    : f.parentPath;
            }
        });
    }

    private Image getStatusImage(FileStatus status) {
        String key = switch (status) {
            case MODIFIED -> "icons/modified.png";
            case ADDED    -> "icons/added.png";
            case DELETED  -> "icons/deleted.png";
            case RENAMED  -> "icons/renamed.png";
            case COPIED   -> "icons/copied.png";
        };
        return Activator.getDefault().getImageRegistry().get(key);
    }

    private void createContextMenu() {
        Menu menu = new Menu(viewer.getControl());

        MenuItem openCompareItem = new MenuItem(menu, SWT.PUSH);
        openCompareItem.setText("Open Compare");
        openCompareItem.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> openCompare()));

        MenuItem openFileItem = new MenuItem(menu, SWT.PUSH);
        openFileItem.setText("Open File");
        openFileItem.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> openFile()));

        viewer.getControl().setMenu(menu);
    }

    private void addToolbarActions() {
        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();

        Action setBranchAction = new Action("Set Target Branch") {
            @Override
            public void run() {
                String current = targetBranch != null ? targetBranch : "";
                InputDialog dialog = new InputDialog(
                    viewer.getControl().getShell(),
                    "Set Target Branch",
                    "Compare HEAD against (e.g. origin/main, main, refs/heads/develop):",
                    current,
                    null);
                if (dialog.open() == Window.OK) {
                    String value = dialog.getValue().trim();
                    targetBranch = value.isEmpty() ? null : value;
                    // Activate QuickDiff on explicit branch selection; the refresh job
                    // then applies the baseline to open editors.
                    quickDiffActive = true;
                    triggerRefresh();
                }
            }
        };
        setBranchAction.setToolTipText("Set the branch to compare against");
        setBranchAction.setImageDescriptor(
            org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(
                "org.eclipse.egit.ui", "icons/obj16/branch_obj.png"));
        toolbar.add(setBranchAction);
        getViewSite().getActionBars().updateActionBars();
    }

    private void registerListeners() {
        workspaceListener = new WorkspaceChangeListener(this::scheduleRefresh);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
            workspaceListener, IResourceChangeEvent.POST_CHANGE);

        // Apply the git QuickDiff ruler to text editors opened after the user selects a branch.
        quickDiffPartListener = new IPartListener2() {
            @Override
            public void partOpened(IWorkbenchPartReference ref) {
                applyQuickDiffTo(ref);
            }
            @Override
            public void partVisible(IWorkbenchPartReference ref) {
                applyQuickDiffTo(ref);
            }
        };
        getSite().getWorkbenchWindow().getPartService().addPartListener(quickDiffPartListener);
    }

    private void applyQuickDiffTo(IWorkbenchPartReference ref) {
        if (!quickDiffActive) return;
        IWorkbenchPart part = ref.getPart(false);
        if (part instanceof ITextEditor editor) {
            baselineManager.applyToEditor(editor, targetBranch);
        }
    }

    private void scheduleRefresh() {
        refreshJob.cancel();
        refreshJob.schedule(200);
    }

    void triggerRefresh() {
        scheduleRefresh();
    }

    private class ScheduledRefreshJob extends Job {
        ScheduledRefreshJob() { super("Refresh Changed Files"); }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            if (monitor.isCanceled()) return Status.CANCEL_STATUS;

            Repository repo = resolveActiveRepository();
            if (repo == null) {
                updateUI(Collections.emptyList(), "No git repository found for active project");
                return Status.OK_STATUS;
            }

            MergeBaseDiffComputer computer = new MergeBaseDiffComputer();
            List<ChangedFile> files = computer.computeDiff(repo, targetBranch);

            if (monitor.isCanceled()) return Status.CANCEL_STATUS;

            ensureIndexDiffListener(repo);

            String warning = computer.getLastWarning();
            String label = (warning != null) ? warning : buildStatusLabel(files, computer, repo);

            updateUI(files, label);

            // Keep the editor QuickDiff baseline in sync with the selected reference branch.
            if (quickDiffActive) {
                Display.getDefault().asyncExec(() -> baselineManager.apply(targetBranch));
            }
            return Status.OK_STATUS;
        }
    }

    private void ensureIndexDiffListener(Repository repo) {
        if (cacheEntries.containsKey(repo)) return;
        try {
            IndexDiffCacheEntry entry = IndexDiffCache.INSTANCE.getIndexDiffCacheEntry(repo);
            if (entry != null) {
                // Fix: each repo gets its own listener instance
                IndexDiffListener listener = new IndexDiffListener(this::scheduleRefresh);
                entry.addIndexDiffChangedListener(listener);
                indexListeners.put(repo, listener);
                cacheEntries.put(repo, entry);
            }
        } catch (Exception e) {
            Activator.getDefault().getLog().error("Failed to register IndexDiffListener", e);
        }
    }

    private String buildStatusLabel(List<ChangedFile> files, MergeBaseDiffComputer computer,
            Repository repo) {
        String base = "unknown";
        if (targetBranch != null) {
            base = targetBranch;
        } else {
            try {
                String branch = repo.getBranch();
                if (branch != null) {
                    var cfg = repo.getConfig();
                    String remote = cfg.getString("branch", branch, "remote");
                    String merge  = cfg.getString("branch", branch, "merge");
                    if (remote != null && merge != null) {
                        base = remote + "/" + merge.replaceFirst("^refs/heads/", "");
                    }
                }
            } catch (Exception ignored) {}
        }
        int total = computer.getTotalCount();
        if (total > files.size()) {
            return String.format("Showing %d of %d files changed vs. %s", files.size(), total, base);
        }
        return String.format("%d file%s changed vs. %s", files.size(),
            files.size() == 1 ? "" : "s", base);
    }

    private void updateUI(List<ChangedFile> files, String label) {
        Display.getDefault().asyncExec(() -> {
            if (viewer.getControl().isDisposed()) return;
            viewer.setInput(files);
            statusLabel.setText(label);
        });
    }

    private Repository resolveActiveRepository() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null) {
                IWorkbenchPage page = window.getActivePage();
                if (page != null) {
                    IEditorPart editor = page.getActiveEditor();
                    if (editor != null) {
                        IEditorInput input = editor.getEditorInput();
                        if (input instanceof FileEditorInput fei) {
                            IProject project = fei.getFile().getProject();
                            return findRepository(project);
                        }
                    }
                }
            }
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                Repository r = findRepository(project);
                if (r != null) return r;
            }
        } catch (Exception e) {
            Activator.getDefault().getLog().error("Failed to resolve repository", e);
        }
        return null;
    }

    private Repository findRepository(IProject project) {
        if (project == null || !project.isOpen()) return null;
        try {
            var builder = new org.eclipse.jgit.storage.file.FileRepositoryBuilder();
            java.io.File projectDir = project.getLocation().toFile();
            builder.findGitDir(projectDir);
            if (builder.getGitDir() == null) return null;
            return builder.build();
        } catch (Exception e) {
            return null;
        }
    }

    private void openFile() {
        Object sel = ((StructuredSelection) viewer.getSelection()).getFirstElement();
        if (!(sel instanceof ChangedFile f)) return;
        if (f.status == FileStatus.DELETED) {
            openCompare();
            return;
        }
        try {
            IWorkbenchPage page = getSite().getPage();
            java.io.File absFile = new java.io.File(f.repository.getWorkTree(), f.fullPath);
            org.eclipse.core.filesystem.IFileStore fileStore =
                org.eclipse.core.filesystem.EFS.getLocalFileSystem().getStore(absFile.toURI());
            org.eclipse.ui.ide.IDE.openEditorOnFileStore(page, fileStore);
        } catch (Exception e) {
            Activator.getDefault().getLog().error("Failed to open file", e);
        }
    }

    private void openCompare() {
        Object sel = ((StructuredSelection) viewer.getSelection()).getFirstElement();
        if (!(sel instanceof ChangedFile f)) return;
        try {
            org.eclipse.egit.ui.internal.revision.GitCompareFileRevisionEditorInput input =
                buildCompareInput(f);
            if (input != null) CompareUI.openCompareEditor(input);
        } catch (Exception e) {
            Activator.getDefault().getLog().error("Failed to open compare editor", e);
        }
    }

    private org.eclipse.egit.ui.internal.revision.GitCompareFileRevisionEditorInput
            buildCompareInput(ChangedFile f) {
        try {
            Repository repo = f.repository;

            org.eclipse.compare.ITypedElement left;
            org.eclipse.compare.ITypedElement right;

            switch (f.source) {
                case STAGED -> {
                    // Left = index, Right = HEAD
                    ObjectId headId = repo.resolve("HEAD");
                    if (headId == null) return null;
                    IFileRevision leftRev = GitFileRevision.inIndex(repo, f.fullPath);
                    try (RevWalk rw = new RevWalk(repo)) {
                        org.eclipse.jgit.revwalk.RevCommit headCommit = rw.parseCommit(headId);
                        String basePath = f.oldPath != null ? f.oldPath : f.fullPath;
                        IFileRevision rightRev = GitFileRevision.inCommit(repo, headCommit, basePath, null, null);
                        left  = new org.eclipse.egit.ui.internal.revision.FileRevisionTypedElement(leftRev, null);
                        right = new org.eclipse.egit.ui.internal.revision.FileRevisionTypedElement(rightRev, null);
                    }
                }
                case UNSTAGED -> {
                    // Left = working tree file, Right = index
                    java.io.File workFile = new java.io.File(repo.getWorkTree(), f.fullPath);
                    left  = new WorkingTreeTypedElement(workFile, f.fileName);
                    IFileRevision rightRev = GitFileRevision.inIndex(repo, f.fullPath);
                    right = new org.eclipse.egit.ui.internal.revision.FileRevisionTypedElement(rightRev, null);
                }
                default -> {
                    // COMMITTED: Left = HEAD, Right = merge-base
                    ObjectId headId = repo.resolve("HEAD");
                    if (headId == null) return null;
                    ObjectId upstreamId = resolveUpstreamForRepo(repo);
                    if (upstreamId == null) return null;
                    ObjectId mergeBaseId = findMergeBaseForRepo(repo, headId, upstreamId);
                    if (mergeBaseId == null) return null;
                    try (RevWalk rw = new RevWalk(repo)) {
                        org.eclipse.jgit.revwalk.RevCommit headCommit = rw.parseCommit(headId);
                        org.eclipse.jgit.revwalk.RevCommit baseCommit = rw.parseCommit(mergeBaseId);
                        IFileRevision leftRev = GitFileRevision.inCommit(repo, headCommit, f.fullPath, null, null);
                        String basePath = f.oldPath != null ? f.oldPath : f.fullPath;
                        IFileRevision rightRev = GitFileRevision.inCommit(repo, baseCommit, basePath, null, null);
                        left  = new org.eclipse.egit.ui.internal.revision.FileRevisionTypedElement(leftRev, null);
                        right = new org.eclipse.egit.ui.internal.revision.FileRevisionTypedElement(rightRev, null);
                    }
                }
            }

            return new org.eclipse.egit.ui.internal.revision.GitCompareFileRevisionEditorInput(
                left, right, null);
        } catch (Exception e) {
            Activator.getDefault().getLog().error("Failed to build compare input", e);
            return null;
        }
    }

    /** ITypedElement that reads a file directly from the working tree. */
    private static class WorkingTreeTypedElement
            implements org.eclipse.compare.ITypedElement,
                       org.eclipse.compare.IStreamContentAccessor {
        private final java.io.File file;
        private final String name;

        WorkingTreeTypedElement(java.io.File file, String name) {
            this.file = file;
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public org.eclipse.swt.graphics.Image getImage() { return null; }
        @Override public String getType() {
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot + 1) : ITypedElement.UNKNOWN_TYPE;
        }
        @Override public java.io.InputStream getContents() throws org.eclipse.core.runtime.CoreException {
            try {
                return new java.io.FileInputStream(file);
            } catch (java.io.FileNotFoundException e) {
                throw new org.eclipse.core.runtime.CoreException(
                    org.eclipse.core.runtime.Status.error("File not found: " + file, e));
            }
        }
    }

    private ObjectId resolveUpstreamForRepo(Repository repo) throws Exception {
        if (targetBranch != null) {
            for (String candidate : new String[]{targetBranch,
                    "refs/remotes/" + targetBranch, "refs/heads/" + targetBranch,
                    "refs/remotes/origin/" + targetBranch}) {
                var id = repo.resolve(candidate);
                if (id != null) return id;
            }
        }
        String branch = repo.getBranch();
        if (branch != null) {
            var cfg = repo.getConfig();
            String remote = cfg.getString("branch", branch, "remote");
            String merge  = cfg.getString("branch", branch, "merge");
            if (remote != null && merge != null) {
                String trackRef = "refs/remotes/" + remote + "/"
                    + merge.replaceFirst("^refs/heads/", "");
                var id = repo.resolve(trackRef);
                if (id != null) return id;
            }
        }
        var id = repo.resolve("refs/remotes/origin/main");
        if (id != null) return id;
        return repo.resolve("refs/remotes/origin/master");
    }

    private ObjectId findMergeBaseForRepo(Repository repo, ObjectId head, ObjectId upstream)
            throws Exception {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(head));
            walk.markStart(walk.parseCommit(upstream));
            var base = walk.next();
            return base != null ? base.getId() : null;
        }
    }

    public static class RefreshHandler extends org.eclipse.core.commands.AbstractHandler {
        @Override
        public Object execute(org.eclipse.core.commands.ExecutionEvent event)
                throws org.eclipse.core.commands.ExecutionException {
            try {
                IWorkbenchPage page = PlatformUI.getWorkbench()
                    .getActiveWorkbenchWindow().getActivePage();
                ChangedFilesView view = (ChangedFilesView) page.findView(ID);
                if (view != null) view.triggerRefresh();
            } catch (Exception e) {
                // ignore
            }
            return null;
        }
    }

    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }

    @Override
    public void dispose() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(workspaceListener);
        if (quickDiffPartListener != null) {
            getSite().getWorkbenchWindow().getPartService().removePartListener(quickDiffPartListener);
            quickDiffPartListener = null;
        }
        // Fix: remove each repo's own listener from its cache entry
        for (Map.Entry<Repository, IndexDiffCacheEntry> e : cacheEntries.entrySet()) {
            IndexDiffListener listener = indexListeners.get(e.getKey());
            if (listener != null) {
                e.getValue().removeIndexDiffChangedListener(listener);
            }
        }
        indexListeners.clear();
        cacheEntries.clear();
        super.dispose();
    }
}
