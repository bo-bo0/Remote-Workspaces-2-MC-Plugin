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

package net.mcreator.vcs.workspace;

import net.mcreator.ui.init.L10N;
import net.mcreator.vcs.SwingTestSupport;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class SshAuthenticationTest extends SwingTestSupport {

	@TempDir Path directory;
	private SshServer server;
	private Git remote;
	private SshdSessionFactory factory;
	private SshSessionFactory previousFactory;
	private Timer driver;
	private Path knownHosts;
	private VCSInfo info;
	private final AtomicInteger passphrasePrompts = new AtomicInteger();
	private final AtomicInteger passwordAttempts = new AtomicInteger();
	private final AtomicReference<Throwable> failure = new AtomicReference<>();

	@BeforeEach void startSshServer() throws Exception {
		remote = Git.init().setBare(true).setDirectory(directory.resolve("remote.git").toFile())
				.setInitialBranch("master").call();
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		var clientKey = generator.generateKeyPair();
		Path ssh = Files.createDirectories(directory.resolve(".ssh"));
		Path privateKey = ssh.resolve("test-key");
		OpenSSHKeyEncryptionContext encryption = new OpenSSHKeyEncryptionContext();
		encryption.setCipherType("256");
		encryption.setPassword("test-passphrase");
		try (OutputStream out = Files.newOutputStream(privateKey)) {
			OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKey, "test", encryption, out);
		}
		server = SshServer.setUpDefaultServer();
		server.setHost("127.0.0.1");
		server.setPort(0);
		var hostKeys = new SimpleGeneratorHostKeyProvider(directory.resolve("host-key"));
		hostKeys.setAlgorithm("RSA");
		hostKeys.setKeySize(2048);
		server.setKeyPairProvider(hostKeys);
		server.setPublickeyAuthenticator((user, key, session) ->
				"git".equals(user) && KeyUtils.compareKeys(clientKey.getPublic(), key));
		server.setPasswordAuthenticator((user, password, session) -> {
			passwordAttempts.incrementAndGet();
			return false;
		});
		server.setCommandFactory((channel, command) -> new GitCommand(command));
		server.start();
		var hostKey = hostKeys.loadKeys(null).iterator().next().getPublic();
		knownHosts = ssh.resolve("known_hosts");
		Files.writeString(knownHosts, "[127.0.0.1]:" + server.getPort() + " " + PublicKeyEntry.toString(hostKey) + "\n");
		Files.writeString(ssh.resolve("config"), "Host test-server\n"
				+ "  HostName 127.0.0.1\n  Port " + server.getPort() + "\n  User git\n"
				+ "  IdentityFile \"" + privateKey.toString().replace('\\', '/') + "\"\n"
				+ "  IdentitiesOnly yes\n  PreferredAuthentications publickey\n  StrictHostKeyChecking yes\n");
		factory = new SshdSessionFactory();
		factory.setHomeDirectory(directory.toFile());
		factory.setSshDirectory(ssh.toFile());
		previousFactory = SshSessionFactory.getInstance();
		SshSessionFactory.setInstance(factory);
		info = new VCSInfo("ssh://test-server/repo.git", "github-account", "account-token-is-not-a-passphrase", false);
		driver = onSwingThread(() -> {
			Timer timer = new Timer(10, event -> {
				for (Window window : Window.getWindows()) {
					if (!(window instanceof JDialog dialog) || !window.isShowing()
							|| !dialog.getTitle().equals(L10N.t("dialog.vcs.info_account_authentication")))
						continue;
					try {
						assertFalse(hasCheckbox(dialog), "A known server must not ask for new host trust");
						if (fillPassphrase(dialog))
							passphrasePrompts.incrementAndGet();
						optionPane(dialog).setValue(JOptionPane.OK_OPTION);
					} catch (Throwable ex) {
						failure.set(ex);
						optionPane(dialog).setValue(JOptionPane.CANCEL_OPTION);
					}
				}
			});
			timer.start();
			return timer;
		});
	}

	@AfterEach void stopSshServer() throws Exception {
		if (driver != null)
			onSwingThread(() -> { driver.stop(); return null; });
		SshSessionFactory.setInstance(previousFactory);
		if (factory != null)
			factory.close();
		if (server != null)
			server.stop(true);
		if (remote != null)
			remote.close();
		if (failure.get() != null)
			throw new AssertionError(failure.get());
	}

	@Test void validationClonePushFetchAndPullUseEncryptedSshKeyAndConfiguredUser() throws Exception {
		try (Git seed = Git.init().setDirectory(directory.resolve("seed").toFile())
				.setInitialBranch("master").call()) {
			Files.writeString(directory.resolve("seed/important.txt"), "original work\n");
			seed.add().addFilepattern(".").call();
			seed.commit().setMessage("base").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
			seed.push().setRemote(remote.getRepository().getDirectory().getAbsolutePath()).add("master").call();
		}
		byte[] trustedHosts = Files.readAllBytes(knownHosts);
		boolean valid = onSwingThread(info::isValid);
		assertTrue(valid);
		try (Git clone = Git.cloneRepository().setURI(info.getRemote())
				.setDirectory(directory.resolve("clone").toFile())
				.setCredentialsProvider(info.getCredentialsProvider(directory.toFile(), null)).call()) {
			assertEquals("original work\n", Files.readString(directory.resolve("clone/important.txt")).replace("\r\n", "\n"));
			Files.writeString(directory.resolve("clone/new.txt"), "new work\n");
			clone.add().addFilepattern(".").call();
			var commit = clone.commit().setMessage("new work").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
			clone.push().setRemote("origin").add("master")
					.setCredentialsProvider(info.getCredentialsProvider(directory.toFile(), null)).call();
			assertEquals(commit, remote.getRepository().resolve(Constants.HEAD));
			clone.fetch().setCredentialsProvider(info.getCredentialsProvider(directory.toFile(), null)).call();
			assertTrue(clone.pull().setCredentialsProvider(info.getCredentialsProvider(directory.toFile(), null))
					.call().isSuccessful());
			assertTrue(clone.status().call().isClean());
		}
		assertTrue(passphrasePrompts.get() >= 5);
		assertEquals(0, passwordAttempts.get());
		assertArrayEquals(trustedHosts, Files.readAllBytes(knownHosts));
	}

	@Test void changedServerKeyIsRejectedAndKnownHostsIsNotOverwritten() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		Files.writeString(knownHosts, "[127.0.0.1]:" + server.getPort() + " "
				+ PublicKeyEntry.toString(generator.generateKeyPair().getPublic()) + "\n");
		byte[] before = Files.readAllBytes(knownHosts);

		assertThrows(TransportException.class, () -> info.isValid());

		assertArrayEquals(before, Files.readAllBytes(knownHosts));
		assertEquals(0, passphrasePrompts.get());
		assertEquals(0, passwordAttempts.get());
	}

	@Test void accountTokenCannotReplacePrivateKeyPassphrase() {
		assertThrows(TransportException.class, () -> Git.lsRemoteRepository().setRemote(info.getRemote())
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider("github-account", "account-token"))
				.call());
		assertEquals(0, passphrasePrompts.get());
		assertEquals(0, passwordAttempts.get());
	}

	@Test void encryptedEd25519KeyIsSupported() throws Exception {
		var key = SecurityUtils.getKeyPairGenerator("Ed25519").generateKeyPair();
		OpenSSHKeyEncryptionContext encryption = new OpenSSHKeyEncryptionContext();
		encryption.setCipherType("256");
		encryption.setPassword("test-passphrase");
		try (OutputStream out = Files.newOutputStream(directory.resolve(".ssh/test-key"))) {
			OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "test", encryption, out);
		}
		server.setPublickeyAuthenticator((user, publicKey, session) ->
				"git".equals(user) && KeyUtils.compareKeys(key.getPublic(), publicKey));

		assertTrue(info.isValid());
		assertTrue(passphrasePrompts.get() > 0);
		assertEquals(0, passwordAttempts.get());
	}

	private boolean fillPassphrase(Container container) {
		boolean filled = false;
		for (Component component : container.getComponents()) {
			if (component instanceof JPasswordField field) {
				field.setText("test-passphrase");
				filled = true;
			} else if (component instanceof Container child) {
				filled |= fillPassphrase(child);
			}
		}
		return filled;
	}

	private boolean hasCheckbox(Container container) {
		for (Component component : container.getComponents())
			if (component instanceof JCheckBox || component instanceof Container child && hasCheckbox(child))
				return true;
		return false;
	}

	private JOptionPane optionPane(Container container) {
		for (Component component : container.getComponents()) {
			if (component instanceof JOptionPane pane)
				return pane;
			if (component instanceof Container child) {
				JOptionPane pane = optionPane(child);
				if (pane != null)
					return pane;
			}
		}
		return null;
	}

	private class GitCommand implements Command {
		private final String command;
		private InputStream in;
		private OutputStream out, err;
		private ExitCallback callback;
		private Thread worker;

		GitCommand(String command) {
			this.command = command;
		}

		@Override public void setInputStream(InputStream in) { this.in = in; }
		@Override public void setOutputStream(OutputStream out) { this.out = out; }
		@Override public void setErrorStream(OutputStream err) { this.err = err; }
		@Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

		@Override public void start(ChannelSession channel, Environment environment) {
			worker = new Thread(() -> {
				try {
					if (command.startsWith("git-upload-pack "))
						new UploadPack(remote.getRepository()).upload(in, out, err);
					else if (command.startsWith("git-receive-pack "))
						new ReceivePack(remote.getRepository()).receive(in, out, err);
					else
						throw new IllegalArgumentException(command);
					callback.onExit(0);
				} catch (Exception ex) {
					failure.set(ex);
					callback.onExit(1);
				}
			}, "TestGitSshCommand");
			worker.setDaemon(true);
			worker.start();
		}

		@Override public void destroy(ChannelSession channel) {
			if (worker != null)
				worker.interrupt();
		}
	}
}

