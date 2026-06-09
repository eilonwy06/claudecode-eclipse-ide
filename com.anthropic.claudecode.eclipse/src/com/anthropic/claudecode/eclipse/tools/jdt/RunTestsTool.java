package com.anthropic.claudecode.eclipse.tools.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElementContainer;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Runs JUnit tests and returns results.
 * Adapted from JDT Bridge's TestHandler.
 */
public class RunTestsTool implements McpTool {

	private static final String JUNIT_LAUNCH_TYPE = "org.eclipse.jdt.junit.launchconfig";
	private static final String JUNIT4_KIND = "org.eclipse.jdt.junit.loader.junit4";
	private static final String JUNIT5_KIND = "org.eclipse.jdt.junit.loader.junit5";

	@Override
	public String toolName() {
		return "runTests";
	}

	@Override
	public String description() {
		return "Run JUnit tests for a class, method, package, or project. "
				+ "Returns pass/fail counts and failure details. "
				+ "Input: fully qualified name of test class/method/package, or project name.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");

		JsonObject props = new JsonObject();

		JsonObject target = new JsonObject();
		target.addProperty("type", "string");
		target.addProperty("description", "Test target: FQN of class/method/package, or project name");
		props.add("target", target);

		JsonObject timeout = new JsonObject();
		timeout.addProperty("type", "integer");
		timeout.addProperty("description", "Timeout in seconds (default: 300)");
		props.add("timeout", timeout);

		schema.add("properties", props);

		JsonArray required = new JsonArray();
		required.add("target");
		schema.add("required", required);

		return schema;
	}

	@Override
	public McpToolResult execute(JsonObject params) {
		try {
			String target = params.has("target") ? params.get("target").getAsString() : null;
			if (target == null || target.isBlank()) {
				return McpToolResult.error("Missing required parameter: target");
			}

			int timeoutSecs = params.has("timeout") ? params.get("timeout").getAsInt() : 300;

			// Resolve target to determine test scope
			TestTarget testTarget = resolveTarget(target);
			if (testTarget == null) {
				return McpToolResult.error("Could not resolve test target: " + target);
			}

			// Create launch configuration
			ILaunchConfiguration config = createLaunchConfig(testTarget);
			if (config == null) {
				return McpToolResult.error("Could not create launch configuration for: " + target);
			}

			// Set up result collector
			TestResultCollector collector = new TestResultCollector();
			JUnitCore.addTestRunListener(collector);

			try {
				// Launch tests
				ILaunch launch = config.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());

				// Wait for completion
				boolean completed = collector.awaitCompletion(timeoutSecs, TimeUnit.SECONDS);

				// Wait a bit more for launch to fully terminate
				int waitCount = 0;
				while (!launch.isTerminated() && waitCount < 30) {
					Thread.sleep(100);
					waitCount++;
				}

				JsonObject result = new JsonObject();
				result.addProperty("target", target);
				result.addProperty("completed", completed);
				result.addProperty("totalCount", collector.getTotalCount());
				result.addProperty("passCount", collector.getPassCount());
				result.addProperty("failCount", collector.getFailCount());
				result.addProperty("errorCount", collector.getErrorCount());
				result.addProperty("skipCount", collector.getSkipCount());

				if (!collector.getFailures().isEmpty()) {
					JsonArray failures = new JsonArray();
					for (TestFailure f : collector.getFailures()) {
						JsonObject fj = new JsonObject();
						fj.addProperty("testName", f.testName);
						fj.addProperty("className", f.className);
						fj.addProperty("status", f.status);
						if (f.trace != null) {
							fj.addProperty("trace", f.trace);
						}
						failures.add(fj);
					}
					result.add("failures", failures);
				}

				return McpToolResult.success(result);
			} finally {
				JUnitCore.removeTestRunListener(collector);
			}
		} catch (Exception e) {
			return McpToolResult.error("Failed to run tests: " + e.getMessage());
		}
	}

	private TestTarget resolveTarget(String target) {
		// Try as method: com.example.FooTest.testBar()
		IJavaElement element = JdtUtils.resolveElement(target);
		if (element instanceof IMethod method) {
			IType type = method.getDeclaringType();
			IJavaProject jp = type.getJavaProject();
			return new TestTarget(jp.getProject(), type.getFullyQualifiedName(), method.getElementName());
		}

		// Try as type
		if (element instanceof IType type) {
			IJavaProject jp = type.getJavaProject();
			return new TestTarget(jp.getProject(), type.getFullyQualifiedName(), null);
		}

		// Try as package
		if (element instanceof IPackageFragment pkg) {
			IJavaProject jp = pkg.getJavaProject();
			return new TestTarget(jp.getProject(), null, null, pkg.getElementName());
		}

		// Try as project name
		for (IJavaProject jp : JdtUtils.getJavaProjects()) {
			if (jp.getElementName().equals(target)) {
				return new TestTarget(jp.getProject(), null, null);
			}
		}

		return null;
	}

	private ILaunchConfiguration createLaunchConfig(TestTarget target) throws Exception {
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationType type = manager.getLaunchConfigurationType(JUNIT_LAUNCH_TYPE);
		if (type == null) {
			return null;
		}

		String configName = "Claude Test Run - " + System.currentTimeMillis();
		ILaunchConfigurationWorkingCopy config = type.newInstance(null, configName);

		config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, target.project.getName());

		// Detect JUnit version (prefer JUnit 5)
		String testKind = detectTestKind(target);
		config.setAttribute("org.eclipse.jdt.junit.TEST_KIND", testKind);

		if (target.className != null) {
			config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, target.className);
			if (target.methodName != null) {
				config.setAttribute("org.eclipse.jdt.junit.TESTNAME", target.methodName);
			}
		} else if (target.packageName != null) {
			config.setAttribute("org.eclipse.jdt.junit.CONTAINER", target.packageName);
		} else {
			// Run all tests in project
			config.setAttribute("org.eclipse.jdt.junit.CONTAINER", "=" + target.project.getName());
		}

		return config;
	}

	private String detectTestKind(TestTarget target) {
		// Check if JUnit 5 is available
		try {
			IJavaProject jp = org.eclipse.jdt.core.JavaCore.create(target.project);
			IType junit5 = jp.findType("org.junit.jupiter.api.Test");
			if (junit5 != null) {
				return JUNIT5_KIND;
			}
		} catch (Exception e) {
			// Fall through
		}
		return JUNIT4_KIND;
	}

	private static class TestTarget {
		final IProject project;
		final String className;
		final String methodName;
		final String packageName;

		TestTarget(IProject project, String className, String methodName) {
			this(project, className, methodName, null);
		}

		TestTarget(IProject project, String className, String methodName, String packageName) {
			this.project = project;
			this.className = className;
			this.methodName = methodName;
			this.packageName = packageName;
		}
	}

	private static class TestFailure {
		final String testName;
		final String className;
		final String status;
		final String trace;

		TestFailure(String testName, String className, String status, String trace) {
			this.testName = testName;
			this.className = className;
			this.status = status;
			this.trace = trace;
		}
	}

	private static class TestResultCollector extends TestRunListener {
		private final CountDownLatch latch = new CountDownLatch(1);
		private final List<TestFailure> failures = new ArrayList<>();
		private int totalCount = 0;
		private int passCount = 0;
		private int failCount = 0;
		private int errorCount = 0;
		private int skipCount = 0;

		@Override
		public void sessionFinished(ITestRunSession session) {
			countResults(session.getChildren());
			totalCount = passCount + failCount + errorCount + skipCount;
			latch.countDown();
		}

		private void countResults(ITestElement[] elements) {
			for (ITestElement element : elements) {
				if (element instanceof ITestCaseElement tc) {
					ITestElement.Result result = tc.getTestResult(false);
					switch (result) {
						case OK -> passCount++;
						case FAILURE -> {
							failCount++;
							addFailure(tc, "FAILURE");
						}
						case ERROR -> {
							errorCount++;
							addFailure(tc, "ERROR");
						}
						case IGNORED -> skipCount++;
						default -> {}
					}
				} else if (element instanceof ITestElementContainer container) {
					countResults(container.getChildren());
				}
			}
		}

		private void addFailure(ITestCaseElement tc, String status) {
			String trace = null;
			try {
				var failureTrace = tc.getFailureTrace();
				if (failureTrace != null) {
					trace = failureTrace.getTrace();
				}
			} catch (Exception e) {
				// Ignore
			}
			failures.add(new TestFailure(
					tc.getTestMethodName(),
					tc.getTestClassName(),
					status,
					trace));
		}

		boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
			return latch.await(timeout, unit);
		}

		int getTotalCount() { return totalCount; }
		int getPassCount() { return passCount; }
		int getFailCount() { return failCount; }
		int getErrorCount() { return errorCount; }
		int getSkipCount() { return skipCount; }
		List<TestFailure> getFailures() { return failures; }
	}
}
