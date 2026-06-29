package net.bluemind.devtools.testrunner.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;

public final class BmMcpConfigFile {

	private static final ILog LOG = Platform.getLog(BmMcpConfigFile.class);

	/** Directory scanned by Claude Code to find the right Eclipse instance. */
	public static final String DIR_NAME = "mcp";
	public static final String FILE_PREFIX = "eclipse-";
	public static final String FILE_SUFFIX = ".json";

	private BmMcpConfigFile() {
	}

	public static Path rootDir() {
		String home = System.getProperty("user.home");
		return Path.of(home, ".config", "bluemind", DIR_NAME);
	}

	public static Path configPath() {
		return rootDir().resolve(FILE_PREFIX + workspaceKey() + FILE_SUFFIX);
	}

	public static void write(BmMcpServer server) {
		Path target = configPath();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("url", server.url());
		payload.put("token", server.token());
		payload.put("authHeader", "Authorization");
		payload.put("authScheme", "Bearer");
		payload.put("workspace", workspacePath());
		payload.put("projects", openProjectNames());
		payload.put("pid", ProcessHandle.current().pid());
		payload.put("writtenAt", System.currentTimeMillis());
		String json = McpJson.write(payload);
		try {
			Files.createDirectories(target.getParent());
			Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			restrictPermissions(tmp);
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (FileAlreadyExistsException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			restrictPermissions(target);
		} catch (IOException e) {
			LOG.error("Failed to write MCP config at " + target + ": " + e.getMessage(), e);
		}
	}

	public static void delete() {
		try {
			Files.deleteIfExists(configPath());
		} catch (IOException e) {
			LOG.warn("Failed to remove MCP config: " + e.getMessage());
		}
	}

	private static String workspacePath() {
		try {
			var loc = Platform.getInstanceLocation();
			if (loc != null && loc.getURL() != null) {
				return new java.io.File(loc.getURL().getFile()).getAbsolutePath();
			}
		} catch (RuntimeException ignored) {
		}
		IPath root = ResourcesPlugin.getWorkspace().getRoot().getLocation();
		return root != null ? root.toOSString() : "unknown";
	}

	private static String workspaceKey() {
		String path = workspacePath();
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(path.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 12);
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(path.hashCode());
		}
	}

	private static java.util.List<String> openProjectNames() {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (p.isOpen()) {
				names.add(p.getName());
			}
		}
		return names;
	}

	private static void restrictPermissions(Path p) {
		try {
			Files.setPosixFilePermissions(p,
					EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Non-POSIX filesystem: best-effort, skip.
		}
	}
}
