package net.bluemind.devtools.gitchanges.quickdiff;

import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.egit.ui.internal.decorators.GitQuickDiffProvider;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.IChangeRulerColumn;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.internal.texteditor.quickdiff.DocumentLineDiffer;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorExtension3;
import org.eclipse.ui.texteditor.quickdiff.IQuickDiffReferenceProvider;
import org.eclipse.ui.texteditor.quickdiff.QuickDiff;

import net.bluemind.devtools.Activator;
import net.bluemind.devtools.gitchanges.git.MergeBaseDiffComputer;

/**
 * Drives Eclipse QuickDiff (the editor's left-margin change ruler) from the reference
 * branch selected in the Working Changes view.
 *
 * <p>Reuses EGit's {@link GitQuickDiffProvider}: the per-repository baseline is set to the
 * merge-base of HEAD and the selected branch, and the git QuickDiff provider is switched on
 * for open text editors so the change bars appear immediately. This mirrors EGit's own
 * "Set Quickdiff Baseline" command plus the ruler menu's "Show Quick Diff Reference" action.
 *
 * <p>Crucially, the baseline must be keyed by EGit's <em>own</em> cached {@link Repository}
 * instance (obtained via {@link RepositoryMapping}), because that is the instance
 * {@code GitDocument} reads it back with. A repository built independently (e.g. via
 * {@code FileRepositoryBuilder}) is a different object and its baseline would be ignored.
 */
public class QuickDiffBaselineManager {

    /** Extension id of EGit's QuickDiff reference provider (see egit.ui plugin.xml). */
    private static final String GIT_PROVIDER_ID =
        "org.eclipse.egit.ui.internal.decorators.GitQuickDiffProvider";

    private final MergeBaseDiffComputer computer = new MergeBaseDiffComputer();

    /**
     * Applies the QuickDiff baseline (merge-base of HEAD and {@code targetBranch}) to every open
     * text editor and turns the git change ruler on for each. Must be called on the UI thread.
     */
    public void apply(String targetBranch) {
        for (ITextEditor editor : openTextEditors()) {
            applyToEditor(editor, targetBranch);
        }
    }

    /**
     * Sets the git QuickDiff baseline for the editor's repository to merge-base(HEAD,
     * targetBranch) and shows the git change ruler. No-op when the file is not tracked by an
     * EGit-shared repository. Must be called on the UI thread.
     */
    public void applyToEditor(ITextEditor editor, String targetBranch) {
        if (editor == null) return;
        IEditorInput input = editor.getEditorInput();
        if (input == null) return;

        // Resolve EGit's own cached Repository for this file — the same instance GitDocument
        // uses to read the baseline back. Null means the project is not shared with EGit.
        IResource resource = ResourceUtil.getResource(input);
        if (resource == null) return;
        RepositoryMapping mapping = RepositoryMapping.getMapping(resource);
        Repository repo = mapping != null ? mapping.getRepository() : null;
        if (repo == null) return;

        try {
            ObjectId base = computer.resolveMergeBase(repo, targetBranch);
            if (base == null) return;
            GitQuickDiffProvider.setBaselineReference(repo, base.name());
        } catch (Exception e) {
            Activator.getDefault().getLog().error("Failed to set QuickDiff baseline", e);
            return;
        }
        enableGitQuickDiff(editor);
    }

    /**
     * Switches the given editor's QuickDiff reference to EGit's git provider and shows the
     * change ruler. Must be called on the UI thread.
     */
    private void enableGitQuickDiff(ITextEditor editor) {
        IQuickDiffReferenceProvider provider =
            new QuickDiff().getReferenceProviderOrDefault(editor, GIT_PROVIDER_ID);
        // getReferenceProviderOrDefault falls back to another provider when git is not
        // applicable (file not shared with git); only proceed for the git provider.
        if (provider == null || !GIT_PROVIDER_ID.equals(provider.getId())) return;

        IDocumentProvider dp = editor.getDocumentProvider();
        IEditorInput input = editor.getEditorInput();
        if (dp == null || input == null) return;
        IAnnotationModel m = dp.getAnnotationModel(input);
        if (!(m instanceof IAnnotationModelExtension model)) return;

        DocumentLineDiffer differ =
            (DocumentLineDiffer) model.getAnnotationModel(IChangeRulerColumn.QUICK_DIFF_MODEL_ID);
        if (differ == null) {
            differ = new DocumentLineDiffer();
            model.addAnnotationModel(IChangeRulerColumn.QUICK_DIFF_MODEL_ID, differ);
        }
        differ.setReferenceProvider(provider);
        if (editor instanceof ITextEditorExtension3 ext3) {
            ext3.showChangeInformation(true);
        }
    }

    private static java.util.List<ITextEditor> openTextEditors() {
        java.util.List<ITextEditor> editors = new java.util.ArrayList<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference ref : page.getEditorReferences()) {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor instanceof ITextEditor textEditor) {
                        editors.add(textEditor);
                    }
                }
            }
        }
        return editors;
    }
}
