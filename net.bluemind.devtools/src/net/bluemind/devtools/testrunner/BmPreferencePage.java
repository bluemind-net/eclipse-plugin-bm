package net.bluemind.devtools.testrunner;

import net.bluemind.devtools.Activator;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
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
		addField(new BooleanFieldEditor(Activator.PREF_POM_WATCH_JRE,
				"Watch POM & sync JRE arguments", getFieldEditorParent()));
		addField(new BooleanFieldEditor(Activator.PREF_POM_WATCH_TARGET,
				"Watch POM & sync target platform", getFieldEditorParent()));
		addField(new BooleanFieldEditor(Activator.PREF_MCP_ENABLED,
				"Enable MCP server for Claude Code (writes ~/.config/bluemind/mcp.json)",
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(Activator.PREF_ICR_INLINE,
				"Show Interactive Code Review chat inline in the editor (uncheck for the popup dialog)",
				getFieldEditorParent()));
		addField(new ComboFieldEditor(Activator.PREF_CONSENT_PROJECTS,
				"Import/remove workspace projects on Claude Code's request", CONSENT_VALUES,
				getFieldEditorParent()));
		addField(new ComboFieldEditor(Activator.PREF_CONSENT_WORKINGSETS,
				"Create/update/remove working sets on Claude Code's request", CONSENT_VALUES,
				getFieldEditorParent()));
	}

	private static final String[][] CONSENT_VALUES = { { "Ask each Eclipse session", "ask" },
			{ "Always allow", "always" }, { "Never allow", "never" } };
}
