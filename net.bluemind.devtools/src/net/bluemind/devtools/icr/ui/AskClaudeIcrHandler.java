package net.bluemind.devtools.icr.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import net.bluemind.devtools.Activator;
import net.bluemind.devtools.icr.model.IcrSessionStore;

/**
 * Editor command: capture the current text selection, ask the user for a
 * question/change request, and open a review thread for it. The thread is
 * anchored to a {@link Position} in the document so the {@code 💬} glyph follows
 * the code as the file is edited.
 */
public class AskClaudeIcrHandler extends AbstractHandler {

	@Override
	public boolean isEnabled() {
		return IcrSessionStore.instance().isActive();
	}

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
		Shell shell = window != null ? window.getShell() : null;

		IcrSessionStore store = IcrSessionStore.instance();
		if (!store.isActive()) {
			info(shell, "No active review session. Run the /icr skill in Claude Code first.");
			return null;
		}

		IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
		if (!(editorPart instanceof ITextEditor textEditor)) {
			info(shell, "Select code in a text editor first.");
			return null;
		}

		ISelection selection = textEditor.getSelectionProvider().getSelection();
		if (!(selection instanceof ITextSelection textSelection) || textSelection.getLength() == 0) {
			info(shell, "Select the code you want to comment on first.");
			return null;
		}

		IEditorInput editorInput = textEditor.getEditorInput();
		if (!(editorInput instanceof FileEditorInput fileInput)) {
			info(shell, "This editor is not backed by a workspace file.");
			return null;
		}
		IFile file = fileInput.getFile();

		IcrCommentDialog dialog = new IcrCommentDialog(shell, textSelection.getText());
		if (dialog.open() != Window.OK) {
			return null;
		}
		String body = dialog.getValue();

		int startLine = textSelection.getStartLine() + 1;
		int endLine = textSelection.getEndLine() + 1;
		String workspacePath = file.getFullPath().toString();
		String absolutePath = file.getLocation() != null ? file.getLocation().toOSString() : workspacePath;

		var thread = store.addThread(workspacePath, absolutePath, textSelection.getText(), body, startLine, endLine);

		// Anchor to the document so the marker tracks edits.
		try {
			IDocumentProvider provider = textEditor.getDocumentProvider();
			IDocument document = provider != null ? provider.getDocument(editorInput) : null;
			if (document != null) {
				Position position = new Position(textSelection.getOffset(), textSelection.getLength());
				document.addPosition(position);
				thread.setAnchor(position);
			}
		} catch (BadLocationException e) {
			Activator.getDefault().getLog().warn("ICR: could not anchor thread position: " + e.getMessage());
		}

		return null;
	}

	private static void info(Shell shell, String message) {
		if (shell == null) {
			shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
					? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell()
					: null;
		}
		MessageDialog.openInformation(shell, "Interactive Code Review", message);
	}
}
