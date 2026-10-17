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

import net.mcreator.vcs.SwingTestSupport;
import net.mcreator.vcs.util.SshCredentialsProvider;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.settings.WorkspaceSettings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VCSInfoTest extends SwingTestSupport {

	@TempDir Path directory;

	@ParameterizedTest
	@ValueSource(strings = { "git@github.com:owner/repo.git", "ssh://git@github.com/owner/repo.git",
			"ssh://my-alias/repo.git", "my-alias:repo.git", "ssh://git@localhost:2222/repo.git" })
	void sshUsesInteractiveKeyAuthenticationWithoutAccountPassword(String remote) {
		var info = new VCSInfo(remote, "account-name", null, true);
		assertTrue(info.isSsh());
		assertInstanceOf(SshCredentialsProvider.class, info.getCredentialsProvider(directory.toFile(), null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://github.com/owner/repo.git", "http://localhost/repo.git",
			"file:///C:/repos/repo.git", "C:/repos/repo.git", "./repo.git", "git://localhost/repo.git", "" })
	void otherTransportsDoNotUseSsh(String remote) {
		assertFalse(VCSInfo.isSsh(remote));
	}

	@Test void httpsPreservesUsernameAndTokenAuthentication() throws Exception {
		var info = new VCSInfo("https://github.com/owner/repo.git", "account-name", "test-token", false);
		var provider = info.getCredentialsProvider(directory.toFile(), null);
		assertInstanceOf(UsernamePasswordCredentialsProvider.class, provider);
		var username = new CredentialItem.Username();
		var password = new CredentialItem.Password();
		assertTrue(provider.get(new URIish(info.getRemote()), username, password));
		assertEquals("account-name", username.getValue());
		assertArrayEquals("test-token".toCharArray(), password.getValue());
	}

	@Test void failedSetupPreservesRepositoryAndDoesNotSaveVcsSettings() throws Exception {
		try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
			Files.writeString(directory.resolve("important.txt"), "keep this work");
			git.add().addFilepattern(".").call();
			var head = git.commit().setMessage("local work").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
			git.getRepository().getConfig().setString("remote", "origin", "url", "https://example.invalid/original");
			git.getRepository().getConfig().save();
			byte[] config = Files.readAllBytes(directory.resolve(".git/config"));
			Workspace workspace = new Workspace(new WorkspaceSettings("test")) {
				@Override public File getWorkspaceFolder() { return directory.toFile(); }
			};
			var info = new VCSInfo(directory.resolve("missing-remote.git").toUri().toString(), "test", "", true);

			boolean initialized = onSwingThread(() -> WorkspaceVCS.initNewVCSWorkspace(workspace, info, null));
			assertFalse(initialized);

			assertTrue(Files.isDirectory(directory.resolve(".git")));
			assertArrayEquals(config, Files.readAllBytes(directory.resolve(".git/config")));
			assertEquals(head, git.getRepository().resolve("HEAD"));
			assertEquals("keep this work", Files.readString(directory.resolve("important.txt")));
			assertFalse(Files.exists(directory.resolve(".mcreator/vcsInfo")));
			assertNull(WorkspaceVCS.getVCSWorkspace(workspace));
		}
	}
}

