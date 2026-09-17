package net.bluemind.devtools.testrunner;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import net.bluemind.devtools.testrunner.WorkspaceSetup.Step;

/**
 * Lets the user pick which bootstrap steps to (re)run — everything checked by
 * default, since this dialog exists precisely to force a step to run again
 * (e.g. after a JDK reinstall, or to re-import projects added by a repo
 * pull), not only to run once on a brand new workspace.
 */
class WorkspaceSetupDialog extends Dialog {

	private final Map<Step, Button> checkboxes = new EnumMap<>(Step.class);
	private EnumSet<Step> selected = EnumSet.noneOf(Step.class);

	WorkspaceSetupDialog(Shell parentShell) {
		super(parentShell);
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	@Override
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		newShell.setText("BlueMind Workspace Setup");
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite composite = (Composite) super.createDialogArea(parent);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 10;
		layout.marginHeight = 10;
		composite.setLayout(layout);

		Label intro = new Label(composite, SWT.WRAP);
		intro.setText("Select the steps to run or re-run:");
		intro.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		for (Step step : Step.values()) {
			Button checkbox = new Button(composite, SWT.CHECK);
			checkbox.setText(step.label);
			checkbox.setSelection(true);
			checkbox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			checkboxes.put(step, checkbox);
		}
		return composite;
	}

	@Override
	protected void okPressed() {
		selected = EnumSet.noneOf(Step.class);
		for (Map.Entry<Step, Button> entry : checkboxes.entrySet()) {
			if (entry.getValue().getSelection()) {
				selected.add(entry.getKey());
			}
		}
		super.okPressed();
	}

	EnumSet<Step> getSelectedSteps() {
		return selected;
	}
}
