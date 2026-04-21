package net.bluemind.devtools.testrunner.mcp;

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
		String stdout,
		String stderr) {

	public record TestFailure(String className, String methodName, boolean error, String trace) {
	}
}
