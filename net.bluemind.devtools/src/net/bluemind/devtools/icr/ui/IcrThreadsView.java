package net.bluemind.devtools.icr.ui;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

import net.bluemind.devtools.Activator;
import net.bluemind.devtools.icr.model.IcrSessionStore;
import net.bluemind.devtools.icr.model.IcrThread;

/**
 * View listing every Interactive Code Review (ICR) thread (inline conversation)
 * in the current session. Double-clicking a thread opens its file and reveals
 * the anchored code. Live-updates as threads are created, answered, or replied
 * to (from the UI or from Claude via the MCP server).
 */
public class IcrThreadsView extends ViewPart {

	public static final String ID = "net.bluemind.devtools.icr.views.threads";

	private TableViewer viewer;
	private Label statusLabel;

	private final IcrSessionStore.Listener storeListener = this::refresh;

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(1, false));

		viewer = new TableViewer(parent,
				SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
		Table table = viewer.getTable();
		table.setHeaderVisible(true);
		table.setLinesVisible(true);
		table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		createColumns();

		viewer.setContentProvider(new IStructuredContentProvider() {
			@Override
			public Object[] getElements(Object input) {
				if (input instanceof List<?> list) {
					return list.toArray();
				}
				return new Object[0];
			}
		});

		statusLabel = new Label(parent, SWT.NONE);
		statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		viewer.addDoubleClickListener(event -> openSelectedThread());

		IcrSessionStore.instance().addListener(storeListener);
		refresh();
	}

	private void createColumns() {
		TableViewerColumn statusCol = new TableViewerColumn(viewer, SWT.NONE);
		statusCol.getColumn().setText("Status");
		statusCol.getColumn().setWidth(90);
		statusCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				IcrThread t = (IcrThread) element;
				if (t.working()) {
					return "⏳ working";
				}
				return t.status() == IcrThread.Status.ANSWERED ? "✓ answered" : "● pending";
			}
		});

		TableViewerColumn fileCol = new TableViewerColumn(viewer, SWT.NONE);
		fileCol.getColumn().setText("File");
		fileCol.getColumn().setWidth(180);
		fileCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				IcrThread t = (IcrThread) element;
				String path = t.workspacePath();
				int slash = path.lastIndexOf('/');
				return slash >= 0 ? path.substring(slash + 1) : path;
			}

			@Override
			public String getToolTipText(Object element) {
				return ((IcrThread) element).workspacePath();
			}
		});

		TableViewerColumn lineCol = new TableViewerColumn(viewer, SWT.NONE);
		lineCol.getColumn().setText("Line");
		lineCol.getColumn().setWidth(60);
		lineCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				IcrThread t = (IcrThread) element;
				return t.startLine() == t.endLine() ? Integer.toString(t.startLine())
						: t.startLine() + "–" + t.endLine();
			}
		});

		TableViewerColumn convCol = new TableViewerColumn(viewer, SWT.NONE);
		convCol.getColumn().setText("Conversation");
		convCol.getColumn().setWidth(360);
		convCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				IcrThread t = (IcrThread) element;
				int replies = t.replies().size();
				String preview = firstLine(t.body());
				return replies == 0 ? preview : preview + "  (" + replies + " repl" + (replies == 1 ? "y" : "ies") + ")";
			}

			@Override
			public String getToolTipText(Object element) {
				return ((IcrThread) element).body();
			}
		});

		org.eclipse.jface.viewers.ColumnViewerToolTipSupport.enableFor(viewer);
	}

	private static String firstLine(String text) {
		if (text == null) {
			return "";
		}
		int nl = text.indexOf('\n');
		String line = nl >= 0 ? text.substring(0, nl) : text;
		line = line.strip();
		return line.length() > 80 ? line.substring(0, 79) + "…" : line;
	}

	/** Re-read the store and repaint, marshalling onto the UI thread. */
	private void refresh() {
		Display.getDefault().asyncExec(() -> {
			if (viewer == null || viewer.getControl().isDisposed()) {
				return;
			}
			List<IcrThread> threads = IcrSessionStore.instance().listThreads();
			viewer.setInput(threads);
			statusLabel.setText(buildStatusLabel(threads));
		});
	}

	private static String buildStatusLabel(List<IcrThread> threads) {
		IcrSessionStore store = IcrSessionStore.instance();
		String session = store.isActive() ? "session active" : "no active session";
		if (threads.isEmpty()) {
			return store.isActive() ? "No conversations yet — select code and press Ctrl+Alt+C to start one."
					: "No conversations. Run the /icr skill in Claude Code to start a review.";
		}
		long pending = threads.stream().filter(t -> t.status() == IcrThread.Status.PENDING).count();
		return String.format("%d conversation%s, %d pending — %s", threads.size(),
				threads.size() == 1 ? "" : "s", pending, session);
	}

	private void openSelectedThread() {
		Object sel = ((StructuredSelection) viewer.getSelection()).getFirstElement();
		if (!(sel instanceof IcrThread thread)) {
			return;
		}
		try {
			IFile file = ResourcesPlugin.getWorkspace().getRoot()
					.getFile(IPath.fromPortableString(thread.workspacePath()));
			IWorkbenchPage page = getSite().getPage();
			IEditorPart part = IDE.openEditor(page, file, true);
			revealThread(part, thread);
		} catch (Exception e) {
			Activator.getDefault().getLog().error("ICR: failed to open thread file", e);
		}
		// Force the 💬 glyph to repaint now that the editor is (re)open.
		IcrEditors.refreshCodeMinings();
	}

	/** Select and reveal the thread's code, preferring its live anchor. */
	private static void revealThread(IEditorPart part, IcrThread thread) {
		if (!(part instanceof ITextEditor editor)) {
			return;
		}
		IDocument document = editor.getDocumentProvider() != null
				? editor.getDocumentProvider().getDocument(editor.getEditorInput())
				: null;

		Position anchor = thread.anchor();
		if (anchor != null && !anchor.isDeleted()) {
			editor.selectAndReveal(anchor.getOffset(), anchor.getLength());
			return;
		}
		if (document == null) {
			return;
		}
		try {
			int startLine = Math.max(0, Math.min(thread.startLine() - 1, document.getNumberOfLines() - 1));
			int endLine = Math.max(startLine, Math.min(thread.endLine() - 1, document.getNumberOfLines() - 1));
			int offset = document.getLineOffset(startLine);
			int length = document.getLineOffset(endLine) + document.getLineLength(endLine) - offset;
			editor.selectAndReveal(offset, length);
		} catch (BadLocationException e) {
			Activator.getDefault().getLog().warn("ICR: could not reveal thread line: " + e.getMessage());
		}
	}

	@Override
	public void setFocus() {
		viewer.getControl().setFocus();
	}

	@Override
	public void dispose() {
		IcrSessionStore.instance().removeListener(storeListener);
		super.dispose();
	}
}
