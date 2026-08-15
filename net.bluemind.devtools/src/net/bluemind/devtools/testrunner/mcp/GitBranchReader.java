package net.bluemind.devtools.testrunner.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** Reads the current branch of a repo root, for workspace_info and the MCP config file. */
final class GitBranchReader {

	private GitBranchReader() {
	}

	static Optional<String> currentBranch(Path repoRoot) {
		if (repoRoot == null) {
			return Optional.empty();
		}
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		builder.findGitDir(repoRoot.toFile());
		if (builder.getGitDir() == null) {
			return Optional.empty();
		}
		try (Repository repo = builder.build()) {
			return Optional.ofNullable(repo.getBranch());
		} catch (IOException e) {
			return Optional.empty();
		}
	}
}
