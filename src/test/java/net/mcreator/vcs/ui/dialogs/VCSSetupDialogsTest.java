package net.mcreator.vcs.ui.dialogs;

import net.mcreator.ui.init.L10N;
import net.mcreator.vcs.SwingTestSupport;
import net.mcreator.vcs.workspace.VCSInfo;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VCSSetupDialogsTest extends SwingTestSupport {

	@TempDir Path directory;

	@Test void sshKeepsCommitIdentityEditableAndCancellationDoesNotChangeSettings() throws Exception {
		VCSInfo existing = new VCSInfo("git@example.invalid:owner/repo.git", "login-name", null, true);
		existing.setCommitIdentity("Public alias", "alias@example.com");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Timer driver = driveDialog(dialog -> {
			JTextField name = field(dialog, "Public alias");
			JTextField email = field(dialog, "alias@example.com");
			assertTrue(name.isEnabled());
			assertTrue(email.isEnabled());
			assertFalse(field(dialog, "login-name").isEnabled());
			name.setText("changed");
			email.setText("changed@example.com");
			components(dialog, JOptionPane.class).getFirst().setValue(JOptionPane.CANCEL_OPTION);
		}, failure);
		try {
			assertNull(onSwingThread(() -> VCSSetupDialogs.getVCSInfoDialog(new JFrame(), "Settings",
					existing.getRemote(), existing.getUsername(), true, false, existing.getCommitName(),
					existing.getCommitEmail())));
			if (failure.get() != null)
				throw new AssertionError(failure.get());
			assertEquals("Public alias", existing.getCommitName());
			assertEquals("alias@example.com", existing.getCommitEmail());
		} finally {
			onSwingThread(() -> { driver.stop(); return null; });
		}
	}

	@Test void submittedIdentityIsSeparateFromAuthenticationAndBlankMeansAnonymous() throws Exception {
		try (Git ignored = Git.init().setBare(true).setDirectory(directory.toFile()).call()) {
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Timer driver = driveDialog(dialog -> {
				field(dialog, "Public alias").setText(" ");
				field(dialog, "alias@example.com").setText(" ");
				components(dialog, JOptionPane.class).getFirst().setValue(L10N.t("dialog.vcs.setup_proceed"));
			}, failure);
			try {
				VCSInfo result = onSwingThread(() -> VCSSetupDialogs.getVCSInfoDialog(new JFrame(), "Settings",
						directory.toUri().toString(), "login-name", true, false, "Public alias", "alias@example.com"));
				if (failure.get() != null)
					throw new AssertionError(failure.get());
				assertNotNull(result);
				assertEquals("login-name", result.getUsername());
				assertEquals("MCreator", result.getCommitName());
				assertEquals("mcreator@localhost", result.getCommitEmail());
				assertEquals(directory.toUri().toString(), result.getRemote());
			} finally {
				onSwingThread(() -> { driver.stop(); return null; });
			}
		}
	}

	private Timer driveDialog(java.util.function.Consumer<JDialog> action, AtomicReference<Throwable> failure)
			throws Exception {
		return onSwingThread(() -> {
			Timer timer = new Timer(10, event -> {
				for (Window window : Window.getWindows()) {
					if (!(window instanceof JDialog dialog) || !dialog.isShowing()
							|| !dialog.getTitle().equals(L10N.t("dialog.vcs.setup_remote_workspace_details")))
						continue;
					((Timer) event.getSource()).stop();
					try {
						action.accept(dialog);
					} catch (Throwable ex) {
						failure.set(ex);
						components(dialog, JOptionPane.class).getFirst().setValue(JOptionPane.CANCEL_OPTION);
					}
				}
			});
			timer.start();
			return timer;
		});
	}

	private JTextField field(Container parent, String text) {
		return components(parent, JTextField.class).stream().filter(field -> field.getText().equals(text))
				.findFirst().orElseThrow();
	}

	private static <T> List<T> components(Container container, Class<T> type) {
		List<T> matches = new ArrayList<>();
		for (Component component : container.getComponents()) {
			if (type.isInstance(component))
				matches.add(type.cast(component));
			if (component instanceof Container child)
				matches.addAll(components(child, type));
		}
		return matches;
	}

}
