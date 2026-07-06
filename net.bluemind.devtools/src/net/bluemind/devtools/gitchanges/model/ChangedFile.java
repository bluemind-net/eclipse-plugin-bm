package net.bluemind.devtools.gitchanges.model;

import org.eclipse.jgit.lib.Repository;

public class ChangedFile {

    public enum ChangeSource { COMMITTED, STAGED, UNSTAGED }

    public final String fileName;
    public final String parentPath;
    public final String fullPath;
    public final String oldPath;      // non-null only for RENAMED / COPIED
    public final FileStatus status;
    public final Repository repository;
    public final ChangeSource source;

    public ChangedFile(String fullPath, String oldPath, FileStatus status, Repository repository) {
        this(fullPath, oldPath, status, repository, ChangeSource.COMMITTED);
    }

    public ChangedFile(String fullPath, String oldPath, FileStatus status, Repository repository,
            ChangeSource source) {
        this.fullPath = fullPath;
        this.oldPath = oldPath;
        this.status = status;
        this.repository = repository;
        this.source = source;
        int slash = fullPath.lastIndexOf('/');
        if (slash < 0) {
            this.fileName = fullPath;
            this.parentPath = "";
        } else {
            this.fileName = fullPath.substring(slash + 1);
            this.parentPath = fullPath.substring(0, slash);
        }
    }
}
