package net.bluemind.devtools.icr.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Small modal popup that asks the user for a question or change request about
 * the code they selected. Shows the selected snippet for context and offers a
 * multiline input. Submit with Ctrl+Enter.
 */
public class IcrCommentDialog extends Dialog {

	private final String selectedText;
	private Text input;
	private String value;

	public IcrCommentDialog(Shell parentShell, String selectedText) {
		super(parentShell);
		this.selectedText = selectedText;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Ask Claude (ICR)");
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 10;
		layout.marginHeight = 10;
		area.setLayout(layout);

		Label prompt = new Label(area, SWT.NONE);
		prompt.setText("Question or change request for the selected code:");
		prompt.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		if (selectedText != null && !selectedText.isBlank()) {
			Text snippet = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
			snippet.setText(trim(selectedText));
			GridData sgd = new GridData(SWT.FILL, SWT.FILL, true, false);
			sgd.heightHint = 80;
			snippet.setLayoutData(sgd);
		}

		input = new Text(area, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
		GridData igd = new GridData(SWT.FILL, SWT.FILL, true, true);
		igd.heightHint = 120;
		igd.widthHint = 480;
		input.setLayoutData(igd);
		input.addTraverseListener(e -> {
			// Ctrl+Enter submits.
			if (e.detail == SWT.TRAVERSE_RETURN && (e.stateMask & SWT.MOD1) != 0) {
				e.doit = false;
				okPressed();
			}
		});

		return area;
	}

	@Override
	protected Control createContents(Composite parent) {
		Control contents = super.createContents(parent);
		if (input != null) {
			input.setFocus();
		}
		return contents;
	}

	@Override
	protected void okPressed() {
		if (input != null) {
			value = input.getText().trim();
		}
		if (value == null || value.isEmpty()) {
			return; // require some text
		}
		super.okPressed();
	}

	public String getValue() {
		return value;
	}

	private static String trim(String s) {
		String[] lines = s.split("\n", -1);
		if (lines.length <= 12) {
			return s;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			sb.append(lines[i]).append('\n');
		}
		sb.append("… (").append(lines.length - 12).append(" more lines)");
		return sb.toString();
	}

	@Override
	protected Control createButtonBar(Composite parent) {
		Control bar = super.createButtonBar(parent);
		// Default the OK button label to something friendlier.
		if (getButton(IDialogConstants.OK_ID) != null) {
			getButton(IDialogConstants.OK_ID).setText("Ask Claude");
		}
		return bar;
	}
}
