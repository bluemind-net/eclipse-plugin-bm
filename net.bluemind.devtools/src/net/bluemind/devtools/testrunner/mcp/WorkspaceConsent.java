package net.bluemind.devtools.testrunner.mcp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import net.bluemind.devtools.Activator;

/**
 * Gates workspace-mutating MCP tools (project import/removal, working sets)
 * behind an explicit user consent, per preference category. Preference values
 * are one of "ask" (default, prompts via a dialog), "always" or "never".
 */
public final class WorkspaceConsent {

	private static final long TIMEOUT_MS = 60_000;
	private static final String ASK = "ask";
	private static final String ALWAYS = "always";
	private static final String NEVER = "never";

	/** In-memory answer for the running Eclipse session, keyed by preference key. */
	private static final Map<String, Boolean> sessionAnswers = new ConcurrentHashMap<>();

	public record Decision(boolean allowed, String reason) {
	}

	private WorkspaceConsent() {
	}

	public static Decision checkProjects(String message) {
		return check(Activator.PREF_CONSENT_PROJECTS, message);
	}

	public static Decision checkWorkingSets(String message) {
		return check(Activator.PREF_CONSENT_WORKINGSETS, message);
	}

	private static Decision check(String prefKey, String message) {
		String pref = Activator.getDefault().getPreferenceStore().getString(prefKey);
		if (ALWAYS.equals(pref)) {
			return new Decision(true, null);
		}
		if (NEVER.equals(pref)) {
			return new Decision(false,
					"disabled by user — change it in Window > Preferences > BlueMind");
		}

		Boolean remembered = sessionAnswers.get(prefKey);
		if (remembered != null) {
			return remembered ? new Decision(true, null) : new Decision(false, "user declined earlier this session");
		}
		return askDialog(prefKey, message);
	}

	private static Decision askDialog(String prefKey, String message) {
		CompletableFuture<Decision> future = new CompletableFuture<>();
		org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
			try {
				Shell shell = activeShell();
				MessageDialogWithToggle dialog = MessageDialogWithToggle.openYesNoQuestion(shell,
						"BlueMind — Claude Code", message, "Remember my choice for this Eclipse session", false,
						null, null);
				boolean allowed = dialog.getReturnCode() == IDialogConstants.YES_ID;
				// The answer holds for the whole Eclipse session regardless of the toggle;
				// the toggle additionally persists it across restarts.
				sessionAnswers.put(prefKey, allowed);
				if (dialog.getToggleState()) {
					Activator.getDefault().getPreferenceStore().setValue(prefKey, allowed ? ALWAYS : NEVER);
				}
				future.complete(new Decision(allowed, allowed ? null : "user declined"));
			} catch (Exception e) {
				future.complete(new Decision(false, "consent dialog failed: " + e.getMessage()));
			}
		});
		try {
			return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			// One-time refusal: neither the session cache nor the preference is touched,
			// so the next call asks again instead of leaving the client hanging.
			return new Decision(false, "consent dialog timed out — treated as a one-time refusal");
		} catch (Exception e) {
			return new Decision(false, "consent check failed: " + e.getMessage());
		}
	}

	private static Shell activeShell() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		return window == null ? null : window.getShell();
	}
}
