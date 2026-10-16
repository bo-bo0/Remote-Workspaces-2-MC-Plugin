package net.mcreator.vcs.util;

import net.mcreator.ui.init.L10N;
import net.mcreator.vcs.SwingTestSupport;
import net.mcreator.vcs.ui.component.MergeHandleComponent;
import net.mcreator.vcs.ui.dialogs.VCSFileMergeDialog;
import net.mcreator.vcs.ui.dialogs.VCSWorkspaceMergeDialog;
import net.mcreator.vcs.util.diff.MergeHandle;
import net.mcreator.vcs.util.diff.ResultSide;
import org.eclipse.jgit.diff.DiffEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ManualMergeDialogTest extends SwingTestSupport {

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void finishWaitsForConfirmationAndPreservesMixedChoices(boolean workspace) throws Exception {
		List<MergeHandle<?>> handles = createHandles(workspace);
		runMerge(workspace, handles, dialog -> {
			List<MergeHandleComponent> components = findComponents(dialog, MergeHandleComponent.class);
			components.stream().filter(component -> findComponents(component, JLabel.class).stream()
					.anyMatch(label -> label.getText().equals(workspace ? "en_us" : "first.txt")))
					.findFirst().orElseThrow().remote.doClick();
			assertEquals(ResultSide.REMOTE, handles.get(0).getResultSide());
			assertEquals(ResultSide.LOCAL, handles.get(1).getResultSide());
		});
		assertEquals(ResultSide.REMOTE, handles.get(0).getResultSide());
		assertEquals(ResultSide.LOCAL, handles.get(1).getResultSide());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void bulkSelectionsUpdateMergeResults(boolean workspace) throws Exception {
		List<MergeHandle<?>> handles = createHandles(workspace);
		runMerge(workspace, handles, dialog -> {
			String prefix = workspace ? "dialog.vcs.element_merge_" : "dialog.vcs.file_merge_";
			button(dialog, prefix + "all_remote").doClick();
			handles.forEach(handle -> assertEquals(ResultSide.REMOTE, handle.getResultSide()));
			button(dialog, prefix + "all_local").doClick();
			handles.forEach(handle -> assertEquals(ResultSide.LOCAL, handle.getResultSide()));
			button(dialog, prefix + "all_remote").doClick();
		});
		handles.forEach(handle -> assertEquals(ResultSide.REMOTE, handle.getResultSide()));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void windowCloseDoesNotApplyUnconfirmedMerge(boolean workspace) throws Exception {
		List<MergeHandle<?>> handles = createHandles(workspace);
		runMerge(workspace, handles, dialog -> {
			dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
			assertTrue(dialog.isShowing());
			dialog.getRootPane().getActionMap().get("net.mcreator.ui.dialogs:CLOSE_DIALOG")
					.actionPerformed(null);
			assertTrue(dialog.isShowing());
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void manualMergeCompletesInsideProgressTaskAndCanRunAgain(boolean workspace) throws Exception {
		for (int attempt = 0; attempt < 2; attempt++) {
			List<MergeHandle<?>> handles = createHandles(workspace);
			DialogProgressMonitor monitor = onSwingThread(() -> new DialogProgressMonitor(null, "Sync"));
			driveDialog(workspace, () -> onSwingThread(() -> DialogProgressMonitor.runTask(monitor, "MergeTest",
					() -> {
						showMerge(workspace, handles);
						return "merged";
					})), dialog -> button(dialog, workspace ? "dialog.vcs.element_merge_all_remote"
					: "dialog.vcs.file_merge_all_remote").doClick());
			handles.forEach(handle -> assertEquals(ResultSide.REMOTE, handle.getResultSide()));
		}
	}

	@Test void emptyFileMergeDoesNotOpenDialog() {
		VCSFileMergeDialog.show(null, List.of());
		assertTrue(java.util.Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing));
	}

	@Test void programmaticSelectionMatchesVisibleSelection() throws Exception {
		onSwingThread(() -> {
			MergeHandle<String> handle = new MergeHandle<>("local", "remote", DiffEntry.ChangeType.MODIFY,
					DiffEntry.ChangeType.MODIFY);
			MergeHandleComponent component = new MergeHandleComponent(new ArrayList<>(), handle);
			component.remote.setSelected(true);
			assertEquals("remote", handle.getSelectedResult());
			component.local.setSelected(true);
			assertEquals("local", handle.getSelectedResult());
			return null;
		});
	}

	private void runMerge(boolean workspace, List<MergeHandle<?>> handles, Consumer<JDialog> interaction)
			throws Exception {
		driveDialog(workspace, () -> {
			showMerge(workspace, handles);
			return "merged";
		}, interaction);
	}

	private void driveDialog(boolean workspace, java.util.concurrent.Callable<String> task,
			Consumer<JDialog> interaction) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicReference<JDialog> shown = new AtomicReference<>();
		CompletableFuture<String> result = new CompletableFuture<>();
		String prefix = workspace ? "dialog.vcs.element_merge_" : "dialog.vcs.file_merge_";
		Timer timer = onSwingThread(() -> {
			Timer driver = new Timer(10, event -> {
				for (Window window : Window.getWindows()) {
					if (!(window instanceof JDialog dialog) || !window.isShowing()
							|| !dialog.getTitle().equals(L10N.t(prefix + "manual_required")))
						continue;
					((Timer) event.getSource()).stop();
					shown.set(dialog);
					try {
						assertTrue(SwingUtilities.isEventDispatchThread());
						assertTrue(dialog.isModal());
						assertFalse(result.isDone());
						interaction.accept(dialog);
						button(dialog, prefix + "finish").doClick();
						assertFalse(dialog.isDisplayable());
					} catch (Throwable ex) {
						failure.set(ex);
					} finally {
						dialog.dispose();
					}
					break;
				}
			});
			driver.start();
			return driver;
		});
		Thread worker = new Thread(() -> {
			try {
				result.complete(task.call());
			} catch (Throwable ex) {
				result.completeExceptionally(ex);
			}
		}, "ManualMergeTest");
		worker.setDaemon(true);
		worker.start();
		try {
			assertEquals("merged", result.get(10, TimeUnit.SECONDS));
			onSwingThread(() -> null);
			assertNotNull(shown.get());
			if (failure.get() != null)
				throw new AssertionError("Manual merge interaction failed", failure.get());
		} finally {
			onSwingThread(() -> {
				timer.stop();
				for (Window window : Window.getWindows())
					window.dispose();
				return null;
			});
		}
	}

	private List<MergeHandle<?>> createHandles(boolean workspace) {
		if (workspace)
			return List.of(new MergeHandle<>("en_us", "en_us", DiffEntry.ChangeType.MODIFY,
					DiffEntry.ChangeType.MODIFY), new MergeHandle<>("it_it", "it_it", DiffEntry.ChangeType.MODIFY,
					DiffEntry.ChangeType.MODIFY));
		List<MergeHandle<?>> handles = new ArrayList<>();
		for (String path : List.of("first.txt", "second.txt")) {
			FileSyncHandle handle = new FileSyncHandle(path);
			handle.setLocalPath(path);
			handle.setRemotePath(path);
			handle.setBaseBytes(new byte[] { 0 });
			handle.setLocalBytes(new byte[] { 1 });
			handle.setRemoteBytes(new byte[] { 2 });
			handles.add(handle.toPathMergeHandle());
		}
		return handles;
	}

	@SuppressWarnings("unchecked")
	private void showMerge(boolean workspace, List<MergeHandle<?>> handles) {
		if (workspace)
			VCSWorkspaceMergeDialog.show(null, new WorkspaceMergeHandles(null, Set.of(), Set.of(), Set.of(),
					Set.of((MergeHandle<String>) handles.get(0), (MergeHandle<String>) handles.get(1)), null));
		else
			VCSFileMergeDialog.show(null, handles.stream().map(handle -> (MergeHandle<FileSyncHandle>) handle)
					.toList());
	}

	private JButton button(Container parent, String key) {
		return findComponents(parent, JButton.class).stream().filter(button -> button.getText().equals(L10N.t(key)))
				.findFirst().orElseThrow();
	}

	private <T extends Component> List<T> findComponents(Container parent, Class<T> type) {
		List<T> matches = new ArrayList<>();
		for (Component component : parent.getComponents()) {
			if (type.isInstance(component))
				matches.add(type.cast(component));
			if (component instanceof Container container)
				matches.addAll(findComponents(container, type));
		}
		return matches;
	}
}
