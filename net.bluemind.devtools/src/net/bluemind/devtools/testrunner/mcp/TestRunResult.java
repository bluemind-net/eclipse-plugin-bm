package net.bluemind.devtools.testrunner.mcp;

import java.nio.file.Path;
import java.util.List;

public record TestRunResult(
		boolean success,
		int total,
		int passed,
		int failed,
		int errored,
		int ignored,
		long durationMs,
		List<TestFailure> failures,
		Path runDir,
		Path stdoutFile,
		Path stderrFile,
		Path failuresFile,
		long stdoutBytes,
		long stderrBytes) {

	public record TestFailure(String className, String methodName, boolean error, String trace) {
	}
}
