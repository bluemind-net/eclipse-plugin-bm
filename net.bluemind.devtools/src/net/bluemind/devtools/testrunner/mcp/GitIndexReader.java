package net.bluemind.devtools.testrunner.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * "Is this file tracked by git?", answered from the git index and nothing else.
 *
 * <p>The question only has one honest answer source: the index. A file can exist on
 * disk, be ignored, be freshly generated, or be a leftover of a deleted API — only
 * {@code DirCache.findEntry} separates "the repo knows this file" from "nobody does".
 * {@code null} is a real answer here: no repository, or the path is outside its work
 * tree. A caller that deletes must treat {@code null} as a refusal, never as "untracked".
 *
 * <p>The {@link DirCache} is read once per repository and kept for the lifetime of the
 * reader: the monorepo index is large, and a batch asks the same question dozens of
 * times. Instances are therefore short-lived — one per tool call — and must be closed,
 * which releases only the repositories this reader opened itself (EGit's cached ones
 * belong to EGit).
 */
final class GitIndexReader implements AutoCloseable {

	private final Map<String, Repository> reposByKey = new HashMap<>();
	private final Map<String, DirCache> cachesByWorkTree = new HashMap<>();
	private final List<Repository> owned = new ArrayList<>();

	/**
	 * @param resource the resource the path belongs to (used to find EGit's own cached
	 *                 repository); may be null, in which case the repository is opened
	 *                 by walking up from the path itself
	 * @param absolute absolute path of the file, which need not exist in Eclipse's
	 *                 resource tree — that is precisely the case being diagnosed
	 * @return TRUE tracked, FALSE untracked, null no repository could be resolved
	 */
	Boolean isTracked(IResource resource, Path absolute) {
		Lookup lookup = lookupFor(resource, absolute);
		if (lookup == null) {
			return null;
		}
		String rel = lookup.workTree().relativize(absolute).toString().replace('\\', '/');
		return lookup.cache().findEntry(rel) >= 0;
	}

	/**
	 * Whether ANY file under the given directory is tracked by git — the whole subtree
	 * at once, not one path. A project directory with zero tracked files is a shell git
	 * would not recognise from any commit: the doctor's discriminant for
	 * {@code stale-project:untracked-shell} (see BmMcpTools#projectsData).
	 *
	 * @return TRUE at least one tracked file, FALSE none, null no repository could be
	 *         resolved (a caller that removes a project must treat this as a refusal,
	 *         same as {@link #isTracked})
	 */
	Boolean hasTrackedFiles(IResource resource, Path directory) {
		Lookup lookup = lookupFor(resource, directory);
		if (lookup == null) {
			return null;
		}
		String rel = lookup.workTree().relativize(directory).toString().replace('\\', '/');
		return lookup.cache().getEntriesWithin(rel).length > 0;
	}

	private record Lookup(Path workTree, DirCache cache) {
	}

	private Lookup lookupFor(IResource resource, Path path) {
		if (path == null) {
			return null;
		}
		Repository repo = repositoryFor(resource, path);
		if (repo == null || repo.isBare()) {
			return null;
		}
		Path workTree = repo.getWorkTree().toPath();
		if (!path.startsWith(workTree)) {
			return null;
		}
		DirCache cache = cachesByWorkTree.computeIfAbsent(workTree.toString(), k -> {
			try {
				return repo.readDirCache();
			} catch (IOException | RuntimeException e) {
				return null;
			}
		});
		return cache == null ? null : new Lookup(workTree, cache);
	}

	private Repository repositoryFor(IResource resource, Path absolute) {
		if (resource != null) {
			RepositoryMapping mapping = RepositoryMapping.getMapping(resource);
			Repository shared = mapping == null ? null : mapping.getRepository();
			if (shared != null) {
				return shared;
			}
		}
		// Not shared with EGit (or no resource at all): open the repository the path
		// lives in. Keyed by the starting directory so a batch pays the lookup once.
		Path dir = absolute.getParent();
		if (dir == null) {
			return null;
		}
		String key = dir.toString();
		if (reposByKey.containsKey(key)) {
			return reposByKey.get(key);
		}
		Repository repo = null;
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		builder.findGitDir(dir.toFile());
		if (builder.getGitDir() != null) {
			try {
				repo = builder.build();
				owned.add(repo);
			} catch (IOException | RuntimeException e) {
				repo = null;
			}
		}
		reposByKey.put(key, repo);
		return repo;
	}

	@Override
	public void close() {
		for (Repository repo : owned) {
			repo.close();
		}
		owned.clear();
		reposByKey.clear();
		cachesByWorkTree.clear();
	}
}
