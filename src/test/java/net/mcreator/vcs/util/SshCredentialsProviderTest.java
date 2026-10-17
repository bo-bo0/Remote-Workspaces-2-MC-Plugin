/*
 * MCreator VCS plugin
 * Copyright (C) 2026, bo bo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.vcs.util;

import net.mcreator.ui.init.L10N;
import net.mcreator.vcs.SwingTestSupport;
import net.mcreator.vcs.ui.dialogs.VCSSetupDialogs;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class SshCredentialsProviderTest extends SwingTestSupport {

	@Test void supportsPassphrasesAndHostVerification() {
		SshCredentialsProvider provider = new SshCredentialsProvider(null);
		assertTrue(provider.isInteractive());
		assertTrue(provider.supports(new CredentialItem.Password(),
				new CredentialItem.StringType("Code", false), new CredentialItem.YesNoType("Trust host"),
				new CredentialItem.InformationalMessage("Fingerprint")));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void hostTrustRequiresAnExplicitSelection(boolean trustHost) throws Exception {
		var secret = new CredentialItem.Password();
		var trust = new CredentialItem.YesNoType("Trust host");
		var provider = new SshCredentialsProvider(null);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Timer driver = onSwingThread(() -> {
			Timer timer = new Timer(10, event -> {
				for (Window window : Window.getWindows()) {
					if (!(window instanceof JDialog dialog) || !window.isShowing()
							|| !dialog.getTitle().equals(L10N.t("dialog.vcs.info_account_authentication")))
						continue;
					((Timer) event.getSource()).stop();
					try {
						JCheckBox checkbox = components(dialog, JCheckBox.class).getFirst();
						assertFalse(checkbox.isSelected());
						checkbox.setSelected(trustHost);
						List<JPasswordField> passwords = components(dialog, JPasswordField.class);
						assertEquals(1, passwords.size());
						passwords.getFirst().setText("test-passphrase");
						components(dialog, JOptionPane.class).getFirst().setValue(JOptionPane.OK_OPTION);
					} catch (Throwable ex) {
						failure.set(ex);
						dialog.dispose();
					}
				}
			});
			timer.start();
			return timer;
		});
		try {
			assertTrue(CompletableFuture.supplyAsync(() -> {
				try {
					return provider.get(new URIish("ssh://git@example.invalid/repository"),
							new CredentialItem.InformationalMessage("Server fingerprint"), secret, trust);
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}).get(10, TimeUnit.SECONDS));
			if (failure.get() != null)
				throw new AssertionError(failure.get());
			assertArrayEquals("test-passphrase".toCharArray(), secret.getValue());
			assertEquals(trustHost, trust.getValue());
		} finally {
			onSwingThread(() -> { driver.stop(); return null; });
			secret.clear();
		}
	}

	@Test void cancellationClearsSecretsAndRejectsHost() throws Exception {
		var secret = new CredentialItem.Password();
		secret.setValue("old secret".toCharArray());
		var trust = new CredentialItem.YesNoType("Trust host");
		Timer driver = onSwingThread(() -> {
			Timer timer = new Timer(10, event -> {
				for (Window window : Window.getWindows())
					if (window instanceof JDialog dialog && window.isShowing()
							&& dialog.getTitle().equals(L10N.t("dialog.vcs.info_account_authentication"))) {
						((Timer) event.getSource()).stop();
						components(dialog, JOptionPane.class).getFirst().setValue(JOptionPane.CANCEL_OPTION);
					}
			});
			timer.start();
			return timer;
		});
		try {
			assertFalse(CompletableFuture.supplyAsync(() -> {
				try {
					return new SshCredentialsProvider(null).get(new URIish("ssh://git@example.invalid/repository"),
							secret, trust);
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}).get(10, TimeUnit.SECONDS));
			assertNull(secret.getValue());
			assertFalse(trust.getValue());
		} finally {
			onSwingThread(() -> { driver.stop(); return null; });
		}
	}

	@Test void setupSelectsKeyAuthenticationForSshAndTokenAuthenticationForHttps() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Timer driver = onSwingThread(() -> {
			Timer timer = new Timer(10, event -> {
				for (Window window : Window.getWindows()) {
					if (!(window instanceof JDialog dialog) || !window.isShowing()
							|| !dialog.getTitle().equals(L10N.t("dialog.vcs.setup_remote_workspace_details")))
						continue;
					((Timer) event.getSource()).stop();
					try {
						JPasswordField password = components(dialog, JPasswordField.class).getFirst();
						assertFalse(password.isEnabled());
						JTextField remote = components(dialog, JTextField.class).stream()
								.filter(field -> field.getText().startsWith("git@github.com:")).findFirst().orElseThrow();
						JButton token = components(dialog, JButton.class).stream().filter(button -> button.getText()
								.equals(L10N.t("dialog.vcs.setup_git_access_token.create"))).findFirst().orElseThrow();
						assertFalse(token.isVisible());
						remote.setText("https://github.com/owner/repo.git");
						assertTrue(password.isEnabled());
						assertTrue(token.isVisible());
					} catch (Throwable ex) {
						failure.set(ex);
					} finally {
						components(dialog, JOptionPane.class).getFirst().setValue(JOptionPane.CANCEL_OPTION);
					}
				}
			});
			timer.start();
			return timer;
		});
		try {
			assertNull(onSwingThread(() -> VCSSetupDialogs.getVCSInfoDialog(new JFrame(), "Test setup",
					"git@github.com:owner/repo.git", "", true, true)));
			if (failure.get() != null)
				throw new AssertionError(failure.get());
		} finally {
			onSwingThread(() -> { driver.stop(); return null; });
		}
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

