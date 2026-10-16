package net.mcreator.vcs.util;

import net.mcreator.vcs.SwingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DialogProgressMonitorTest extends SwingTestSupport {

	@Test void fastTasksCompleteAndMonitorCanBeReused() throws Exception {
		DialogProgressMonitor monitor = onSwingThread(() -> new DialogProgressMonitor(null, "Fast sync"));
		for (int attempt = 0; attempt < 20; attempt++) {
			int expected = attempt;
			assertEquals(expected, onSwingThread(() -> DialogProgressMonitor.runTask(monitor, "FastSync",
					() -> expected)));
		}
		assertNoVisibleWindows();
	}

	@Test void backgroundCallerShowsAndClosesProgressOnSwingThread() throws Exception {
		AtomicBoolean allUpdatesOnSwingThread = new AtomicBoolean(true);
		DialogProgressMonitor monitor = onSwingThread(() -> new DialogProgressMonitor(null, "Background sync") {
			@Override public void setVisible(boolean visible) {
				if (!SwingUtilities.isEventDispatchThread())
					allUpdatesOnSwingThread.set(false);
				super.setVisible(visible);
			}
		});
		assertEquals("done", DialogProgressMonitor.runTask(monitor, null, () -> "done"));
		assertTrue(allUpdatesOnSwingThread.get());
		assertNoVisibleWindows();
	}

	@Test void taskRunsOutsideSwingThread() throws Exception {
		DialogProgressMonitor monitor = onSwingThread(() -> new DialogProgressMonitor(null, "Sync"));
		assertFalse(onSwingThread(() -> DialogProgressMonitor.runTask(monitor, "SyncTest",
				SwingUtilities::isEventDispatchThread)));
	}

	@Test void checkedExceptionClosesProgressAndIsPropagated() throws Exception {
		DialogProgressMonitor monitor = onSwingThread(() -> new DialogProgressMonitor(null, "Failed sync"));
		IOException expected = new IOException("Fetch failed");
		assertSame(expected, assertThrows(IOException.class, () -> DialogProgressMonitor.runTask(monitor, "Failure",
				() -> {
					throw expected;
				})));
		assertNoVisibleWindows();
		assertEquals("retry", DialogProgressMonitor.runTask(monitor, "Retry", () -> "retry"));
	}

	@Test void errorClosesProgressAndIsPropagated() throws Exception {
		DialogProgressMonitor monitor = onSwingThread(() -> new DialogProgressMonitor(null, "Failed sync"));
		AssertionError expected = new AssertionError("Task failed");
		assertSame(expected, assertThrows(AssertionError.class, () -> DialogProgressMonitor.runTask(monitor, "Error",
				() -> {
					throw expected;
				})));
		assertNoVisibleWindows();
	}

	private void assertNoVisibleWindows() throws Exception {
		onSwingThread(() -> {
			for (Window window : Window.getWindows())
				assertFalse(window.isShowing());
			return null;
		});
	}
}
