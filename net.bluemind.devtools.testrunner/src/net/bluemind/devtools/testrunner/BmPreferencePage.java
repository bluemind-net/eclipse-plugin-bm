package net.bluemind.devtools.testrunner;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class BmPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public BmPreferencePage() {
		super(GRID);
		setDescription("BlueMind Developer Tools");
	}

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(Activator.getDefault().getPreferenceStore());
	}

	@Override
	protected void createFieldEditors() {
		addField(new BooleanFieldEditor("codeMining.enabled",
				"Show Run/Debug code mining above test methods", getFieldEditorParent()));
	}
}
