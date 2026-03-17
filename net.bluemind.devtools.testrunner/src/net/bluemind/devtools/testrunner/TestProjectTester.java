package net.bluemind.devtools.testrunner;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.jdt.core.IJavaElement;

public class TestProjectTester extends PropertyTester {

	@Override
	public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
		if ("isInTestProject".equals(property) && receiver instanceof IJavaElement je) {
			return je.getJavaProject().getProject().getName().endsWith(".tests");
		}
		return false;
	}
}
