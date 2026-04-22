package net.bluemind.devtools.testrunner.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

public final class BmMcpRunStore {

	private static final ILog LOG = Platform.getLog(BmMcpRunStore.class);
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final int KEEP_LAST = 50;

	private BmMcpRunStore() {
	}

	public static Path runsRoot() {
		String xdg = System.getenv("XDG_CACHE_HOME");
		Path base = (xdg == null || xdg.isBlank())
				? Path.of(System.getProperty("user.home"), ".cache")
				: Path.of(xdg);
		return base.resolve("bluemind").resolve("mcp").resolve("runs");
	}

	public static Path allocate(String slug) throws IOException {
		Path root = runsRoot();
		Files.createDirectories(root);
		String base = STAMP.format(LocalDateTime.now()) + "-" + sanitize(slug);
		Path dir = root.resolve(base);
		int i = 1;
		while (Files.exists(dir)) {
			dir = root.resolve(base + "-" + i++);
		}
		Files.createDirectories(dir);
		return dir;
	}

	public static void prune() {
		Path root = runsRoot();
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> stream = Files.list(root)) {
			stream.filter(Files::isDirectory)
					.sorted(Comparator.comparing(BmMcpRunStore::mtime).reversed())
					.skip(KEEP_LAST)
					.forEach(BmMcpRunStore::deleteRecursively);
		} catch (IOException e) {
			LOG.warn("Run store prune failed: " + e.getMessage());
		}
	}

	private static long mtime(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	private static void deleteRecursively(Path p) {
		try (Stream<Path> walk = Files.walk(p)) {
			walk.sorted(Comparator.reverseOrder()).forEach(BmMcpRunStore::deleteQuiet);
		} catch (IOException e) {
			LOG.warn("Could not delete old run dir " + p + ": " + e.getMessage());
		}
	}

	private static void deleteQuiet(Path p) {
		try {
			Files.deleteIfExists(p);
		} catch (IOException ignored) {
		}
	}

	private static String sanitize(String s) {
		if (s == null || s.isBlank()) {
			return "run";
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length() && sb.length() < 60; i++) {
			char c = s.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
				sb.append(c);
			} else if (c == '#' || c == '/' || c == ':') {
				sb.append('_');
			}
		}
		return sb.length() == 0 ? "run" : sb.toString();
	}

	public static Optional<Long> size(Path p) {
		try {
			return Optional.of(Files.size(p));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	public static String humanSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format("%.1f KB", bytes / 1024.0);
		}
		return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
	}
}
