package net.bluemind.devtools.testrunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

public class BmTestCodeMiningProvider extends AbstractCodeMiningProvider {

	private static final ILog LOG = Platform.getLog(BmTestCodeMiningProvider.class);
	private static final Set<String> TEST_ANNOTATIONS = Set.of(
			"Test", "org.junit.jupiter.api.Test",
			"ParameterizedTest", "org.junit.jupiter.params.ParameterizedTest",
			"RepeatedTest", "org.junit.jupiter.api.RepeatedTest");

	@Override
	public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(
			ITextViewer viewer, IProgressMonitor monitor) {

		if (!Activator.getDefault().getPreferenceStore().getBoolean("codeMining.enabled")) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		IDocument document = viewer.getDocument();

		return CompletableFuture.supplyAsync(() -> {
			ICompilationUnit cu = getCompilationUnit();
			if (cu == null) {
				return Collections.<ICodeMining>emptyList();
			}

			try {
				String projectName = cu.getJavaProject().getProject().getName();
				if (!projectName.endsWith(".tests")) {
					return Collections.<ICodeMining>emptyList();
				}
			} catch (Exception e) {
				return Collections.<ICodeMining>emptyList();
			}

			List<ICodeMining> minings = new ArrayList<>();
			try {
				for (IType type : cu.getTypes()) {
					if (Flags.isAbstract(type.getFlags())) {
						continue;
					}
					addClassMinings(minings, type, document);
					addMethodMinings(minings, type, document);
				}
			} catch (Exception e) {
				LOG.error("Failed to compute test code minings", e);
			}
			return minings;
		});
	}

	private void addClassMinings(List<ICodeMining> minings, IType type, IDocument document)
			throws JavaModelException {
		var nameRange = type.getNameRange();
		if (nameRange == null) {
			return;
		}
		try {
			int line = document.getLineOfOffset(nameRange.getOffset());
			minings.add(new TestCodeMining(line, document, this, "\u25B6 Run", e -> {
				BmTestLaunchShortcut.launchElement(type, null, "run");
			}));
			minings.add(new TestCodeMining(line, document, this, "\u25B6 Debug", e -> {
				BmTestLaunchShortcut.launchElement(type, null, "debug");
			}));
		} catch (BadLocationException e) {
			// ignore
		}
	}

	private void addMethodMinings(List<ICodeMining> minings, IType type, IDocument document)
			throws JavaModelException {
		for (IMethod method : type.getMethods()) {
			if (!isTestMethod(method)) {
				continue;
			}
			var nameRange = method.getNameRange();
			if (nameRange == null) {
				continue;
			}
			try {
				int line = document.getLineOfOffset(nameRange.getOffset());
				String methodName = method.getElementName();
				minings.add(new TestCodeMining(line, document, this, "\u25B6 Run", e -> {
					BmTestLaunchShortcut.launchElement(type, methodName, "run");
				}));
				minings.add(new TestCodeMining(line, document, this, "\u25B6 Debug", e -> {
					BmTestLaunchShortcut.launchElement(type, methodName, "debug");
				}));
			} catch (BadLocationException e) {
				continue;
			}
		}
	}

	private static boolean isTestMethod(IMethod method) throws JavaModelException {
		for (IAnnotation annotation : method.getAnnotations()) {
			if (TEST_ANNOTATIONS.contains(annotation.getElementName())) {
				return true;
			}
		}
		return false;
	}

	private static ICompilationUnit getCompilationUnit() {
		ICompilationUnit[] result = new ICompilationUnit[1];
		Display.getDefault().syncExec(() -> {
			try {
				IEditorPart editor = PlatformUI.getWorkbench()
						.getActiveWorkbenchWindow().getActivePage().getActiveEditor();
				if (editor == null) {
					return;
				}
				IJavaElement element = JavaUI.getEditorInputJavaElement(editor.getEditorInput());
				if (element instanceof ICompilationUnit cu) {
					result[0] = cu;
				}
			} catch (Exception e) {
				// ignore
			}
		});
		return result[0];
	}

	private static class TestCodeMining extends LineHeaderCodeMining {

		TestCodeMining(int line, IDocument document, BmTestCodeMiningProvider provider,
				String label, Consumer<MouseEvent> action) throws BadLocationException {
			super(line, document, provider, action);
			setLabel(label);
		}
	}
}
