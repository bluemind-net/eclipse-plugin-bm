package net.bluemind.devtools.testrunner;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

public class PomSyncHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
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
