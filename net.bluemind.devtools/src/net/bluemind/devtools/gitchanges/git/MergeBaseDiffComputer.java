package net.bluemind.devtools.gitchanges.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import net.bluemind.devtools.gitchanges.model.ChangedFile;
import net.bluemind.devtools.gitchanges.model.ChangedFile.ChangeSource;
import net.bluemind.devtools.gitchanges.model.FileStatus;

public class MergeBaseDiffComputer {

    private static final int MAX_FILES = 500;

    private String lastWarning;
    private int totalCount;

    /** Returns up to MAX_FILES changed files; sets lastWarning on special conditions. */
    public List<ChangedFile> computeDiff(Repository repo) {
        return computeDiff(repo, null);
    }

    /**
     * Like {@link #computeDiff(Repository)}, but uses {@code overrideBranch} as the
     * upstream ref instead of auto-detecting it (e.g. "origin/main", "refs/heads/main").
     */
    public List<ChangedFile> computeDiff(Repository repo, String overrideBranch) {
        lastWarning = null;
        totalCount = 0;

        try {
            ObjectId headId = repo.resolve("HEAD");
            if (headId == null) {
                lastWarning = "No commits yet";
                return Collections.emptyList();
            }

            ObjectId upstreamId = (overrideBranch != null && !overrideBranch.isBlank())
                ? resolveRef(repo, overrideBranch)
                : resolveUpstream(repo);
            if (upstreamId == null) {
                lastWarning = "No upstream branch found";
                return Collections.emptyList();
            }

            ObjectId mergeBaseId = findMergeBase(repo, headId, upstreamId);
            if (mergeBaseId == null) {
                lastWarning = "No merge-base found";
                return Collections.emptyList();
            }

            // Keyed by path so later diffs don't overwrite committed changes for same path
            Map<String, ChangedFile> byPath = new LinkedHashMap<>();

            // 1. Committed changes: merge-base → HEAD
            for (DiffEntry entry : computeTreeDiff(repo, mergeBaseId, headId)) {
                ChangedFile cf = toChangedFile(entry, repo, ChangeSource.COMMITTED);
                byPath.put(cf.fullPath, cf);
            }

            // 2. Staged changes: HEAD → index (only add paths not already present)
            try {
                for (DiffEntry entry : new Git(repo).diff().setCached(true).call()) {
                    ChangedFile cf = toChangedFile(entry, repo, ChangeSource.STAGED);
                    byPath.putIfAbsent(cf.fullPath, cf);
                }
            } catch (Exception ignored) {}

            // 3. Unstaged changes: index → working tree (only add paths not already present)
            try {
                for (DiffEntry entry : new Git(repo).diff().call()) {
                    ChangedFile cf = toChangedFile(entry, repo, ChangeSource.UNSTAGED);
                    byPath.putIfAbsent(cf.fullPath, cf);
                }
            } catch (Exception ignored) {}

            totalCount = byPath.size();
            List<ChangedFile> result = new ArrayList<>();
            for (ChangedFile cf : byPath.values()) {
                if (result.size() >= MAX_FILES) break;
                result.add(cf);
            }
            return result;

        } catch (Exception e) {
            lastWarning = "Error computing diff: " + e.getMessage();
            return Collections.emptyList();
        }
    }

    /**
     * Resolves the commit that the QuickDiff baseline / diff should be taken against:
     * the merge-base of HEAD and the given branch (auto-detected upstream when
     * {@code overrideBranch} is null/blank). Returns null when it cannot be determined.
     */
    public ObjectId resolveMergeBase(Repository repo, String overrideBranch) throws IOException {
        ObjectId headId = repo.resolve("HEAD");
        if (headId == null) return null;
        ObjectId upstreamId = (overrideBranch != null && !overrideBranch.isBlank())
            ? resolveRef(repo, overrideBranch)
            : resolveUpstream(repo);
        if (upstreamId == null) return null;
        return findMergeBase(repo, headId, upstreamId);
    }

    /** Resolves a user-supplied branch name to an ObjectId, trying common ref prefixes. */
    private ObjectId resolveRef(Repository repo, String branch) throws IOException {
        // Try as-is first, then with common prefixes
        for (String candidate : new String[]{branch, "refs/remotes/" + branch,
                "refs/heads/" + branch, "refs/remotes/origin/" + branch}) {
            ObjectId id = repo.resolve(candidate);
            if (id != null) return id;
        }
        return null;
    }

    /**
     * Resolves the upstream ref to diff against.
     * Order: configured tracking branch → origin/main → origin/master.
     */
    private ObjectId resolveUpstream(Repository repo) throws IOException {
        String branch = repo.getBranch();
        if (branch != null) {
            StoredConfig cfg = repo.getConfig();
            String remote = cfg.getString("branch", branch, "remote");
            String merge = cfg.getString("branch", branch, "merge");
            if (remote != null && merge != null) {
                // Convert refs/heads/main → refs/remotes/origin/main
                String trackingRef = "refs/remotes/" + remote + "/"
                    + merge.replaceFirst("^refs/heads/", "");
                ObjectId id = repo.resolve(trackingRef);
                if (id != null) return id;
            }
        }
        // Fallbacks
        ObjectId id = repo.resolve("refs/remotes/origin/main");
        if (id != null) return id;
        return repo.resolve("refs/remotes/origin/master");
    }

    private ObjectId findMergeBase(Repository repo, ObjectId head, ObjectId upstream)
            throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(head));
            walk.markStart(walk.parseCommit(upstream));
            RevCommit base = walk.next();
            return base != null ? base.getId() : null;
        }
    }

    private List<DiffEntry> computeTreeDiff(Repository repo, ObjectId baseId, ObjectId headId)
            throws Exception {
        try (Git git = new Git(repo)) {
            AbstractTreeIterator oldTree = prepareTreeParser(repo, baseId);
            AbstractTreeIterator newTree = prepareTreeParser(repo, headId);
            return git.diff()
                .setOldTree(oldTree)
                .setNewTree(newTree)
                .call();
        }
    }

    private AbstractTreeIterator prepareTreeParser(Repository repo, ObjectId commitId)
            throws IOException {
        if (commitId == null) return new EmptyTreeIterator();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(commitId);
            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (var reader = repo.newObjectReader()) {
                parser.reset(reader, commit.getTree().getId());
            }
            return parser;
        }
    }

    private ChangedFile toChangedFile(DiffEntry entry, Repository repo, ChangeSource source) {
        FileStatus status = switch (entry.getChangeType()) {
            case ADD -> FileStatus.ADDED;
            case MODIFY -> FileStatus.MODIFIED;
            case DELETE -> FileStatus.DELETED;
            case RENAME -> FileStatus.RENAMED;
            case COPY -> FileStatus.COPIED;
        };
        String fullPath = entry.getChangeType() == DiffEntry.ChangeType.DELETE
            ? entry.getOldPath()
            : entry.getNewPath();
        String oldPath = (entry.getChangeType() == DiffEntry.ChangeType.RENAME
            || entry.getChangeType() == DiffEntry.ChangeType.COPY)
            ? entry.getOldPath()
            : null;
        return new ChangedFile(fullPath, oldPath, status, repo, source);
    }

    /** Warning message from last computeDiff(), or null if none. */
    public String getLastWarning() { return lastWarning; }

    /** Total diff entries before truncation. */
    public int getTotalCount() { return totalCount; }
}
