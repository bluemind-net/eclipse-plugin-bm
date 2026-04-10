package net.bluemind.devtools.testrunner;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

public class PomSyncHandler extends AbstractHandler {

	@Override
	public boolean isEnabled() {
		return BmContext.instance().isAvailable();
	}

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		if (!BmContext.instance().isAvailable()) {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			Shell shell = window != null ? window.getShell() : null;
			if (shell != null) {
				MessageDialog.openInformation(shell, "BlueMind POM Sync",
						"No BlueMind global POM found in the workspace. Please import a BlueMind project.");
			}
			return null;
		}

		Job job = new Job("Checking BlueMind POM sync") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				PomSyncChecker.checkAndPrompt(true);
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.setSystem(true);
		job.schedule();
		return null;
	}
}
