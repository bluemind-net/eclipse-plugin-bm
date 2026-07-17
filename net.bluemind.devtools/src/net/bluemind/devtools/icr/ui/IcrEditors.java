package net.bluemind.devtools.icr.ui;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Editor-side helpers for ICR: resolving the workspace path of a document, and
 * forcing every open text editor to recompute its code minings (so the
 * {@code 💬} glyphs appear/refresh immediately after the model changes).
 */
public final class IcrEditors {

	private IcrEditors() {
	}

	/**
	 * The workspace-relative path of the file backing {@code document}, or
	 * {@code null} if it is not a file-backed document. Uses the file-buffer
	 * manager so it works regardless of which editor is active.
	 */
	public static String workspacePath(IDocument document) {
		if (document == null) {
			return null;
		}
		ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(document);
		if (buffer == null) {
			return null;
		}
		IPath location = buffer.getLocation();
		return location == null ? null : location.makeAbsolute().toString();
	}

	/**
	 * The {@link ITextViewer} of the open text editor backed by
	 * {@code workspacePath}, or {@code null} if no such editor is currently open.
	 * When the file is open in more than one editor/split, the first match found
	 * is returned. Must be called on the UI thread.
	 */
	public static ITextViewer findViewer(String workspacePath) {
		if (workspacePath == null || !PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IEditorReference ref : page.getEditorReferences()) {
					IEditorPart editor = ref.getEditor(false);
					if (editor == null) {
						continue;
					}
					Object target = editor.getAdapter(ITextOperationTarget.class);
					if (!(target instanceof ITextViewer viewer)) {
						continue;
					}
					if (workspacePath.equals(workspacePath(viewer.getDocument()))) {
						return viewer;
					}
				}
			}
		}
		return null;
	}

	/** Recompute code minings in all open text editors, on the UI thread. */
	public static void refreshCodeMinings() {
		Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(IcrEditors::doRefreshCodeMinings);
	}

	private static void doRefreshCodeMinings() {
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IEditorReference ref : page.getEditorReferences()) {
					IEditorPart editor = ref.getEditor(false);
					if (editor == null) {
						continue;
					}
					Object target = editor.getAdapter(ITextOperationTarget.class);
					if (target instanceof ISourceViewerExtension5 ext) {
						ext.updateCodeMinings();
					}
				}
			}
		}
	}
}
