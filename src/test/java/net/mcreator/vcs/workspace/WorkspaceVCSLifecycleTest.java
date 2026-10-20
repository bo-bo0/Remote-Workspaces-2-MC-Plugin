package net.mcreator.vcs.workspace;

import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.WorkspaceFolderManager;
import net.mcreator.workspace.settings.WorkspaceSettings;
import net.mcreator.vcs.SwingTestSupport;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceVCSLifecycleTest extends SwingTestSupport {

	@TempDir Path directory;

	@Test void unlinkPreservesHistoryAndCanReconnectWithoutReinitializingRepository() throws Exception {
		Workspace workspace = workspace();
		try (Git git = Git.init().setInitialBranch("local").setDirectory(directory.toFile()).call();
				Git server = Git.init().setBare(true).setDirectory(directory.resolve("server").toFile()).call()) {
			Path file = directory.resolve("workspace.txt");
			Files.writeString(file, "local work");
			git.add().addFilepattern("workspace.txt").call();
			var head = git.commit().setMessage("local").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
			VCSInfo info = new VCSInfo(server.getRepository().getDirectory().toURI().toString(), "", "", true);
			git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(info.getRemote())).call();
			Files.writeString(file, "stashed work");
			var stash = git.stashCreate().setPerson(info.getCommitIdentity()).call();
			Files.writeString(file, "uncommitted work");
			Path settings = directory.resolve(".mcreator/vcsInfo");
			VCSInfo.saveToFile(info, settings.toFile());
			byte[] config = Files.readAllBytes(directory.resolve(".git/config"));
			try {
				assertTrue(WorkspaceVCS.loadVCSWorkspace(workspace));
				WorkspaceVCS.removeVCSWorkspace(workspace);
				assertNull(WorkspaceVCS.getVCSWorkspace(workspace));
				assertFalse(Files.exists(settings));
				VCSInfo reconnect = new VCSInfo(info.getRemote(), "", "", true);
				assertTrue(onSwingThread(() -> WorkspaceVCS.initNewVCSWorkspace(workspace, reconnect, null)));
				assertArrayEquals(config, Files.readAllBytes(directory.resolve(".git/config")));
				assertEquals(head, git.getRepository().resolve("HEAD"));
				assertEquals(stash, git.stashList().call().iterator().next());
				assertEquals("uncommitted work", Files.readString(file));
			} finally {
				WorkspaceVCS.releaseVCSWorkspace(workspace);
			}
		}
	}

	@Test void releaseIsIdempotentAndPreservesRepositorySettingsAndStashes() throws Exception {
		Workspace first = workspace();
		Workspace replacement = workspace();
		VCSInfo info = new VCSInfo("git@example.invalid:owner/repository", "", null, true);
		try (Git git = Git.init().setInitialBranch("local").setDirectory(directory.toFile()).call()) {
			Path file = directory.resolve("workspace.txt");
			Files.writeString(file, "base");
			git.add().addFilepattern(".").call();
			var head = git.commit().setMessage("base").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
			git.getRepository().getConfig().setString("remote", "origin", "url", info.getRemote());
			git.getRepository().getConfig().save();
			Files.writeString(file, "stashed work");
			var stash = git.stashCreate().setPerson(info.getCommitIdentity()).call();
			Path settings = directory.resolve(".mcreator/vcsInfo");
			VCSInfo.saveToFile(info, settings.toFile());
			byte[] config = Files.readAllBytes(directory.resolve(".git/config"));
			byte[] settingsBefore = Files.readAllBytes(settings);
			try {
				assertTrue(WorkspaceVCS.loadVCSWorkspace(first));
				assertNotNull(WorkspaceVCS.getVCSWorkspace(first));
				assertTrue(WorkspaceVCS.loadVCSWorkspace(replacement));
				WorkspaceVCS current = WorkspaceVCS.getVCSWorkspace(replacement);
				WorkspaceVCS.releaseVCSWorkspace(first);
				assertSame(current, WorkspaceVCS.getVCSWorkspace(replacement));
				WorkspaceVCS.releaseVCSWorkspace(replacement);
				WorkspaceVCS.releaseVCSWorkspace(replacement);
				assertNull(WorkspaceVCS.getVCSWorkspace(replacement));
				assertArrayEquals(config, Files.readAllBytes(directory.resolve(".git/config")));
				assertArrayEquals(settingsBefore, Files.readAllBytes(settings));
				try (Git reopened = Git.open(directory.toFile())) {
					assertEquals(head, reopened.getRepository().resolve("HEAD"));
					assertEquals(stash, reopened.stashList().call().iterator().next());
					assertEquals("base", Files.readString(file));
				}
			} finally {
				WorkspaceVCS.releaseVCSWorkspace(first);
				WorkspaceVCS.releaseVCSWorkspace(replacement);
			}
		}
	}

	private Workspace workspace() throws Exception {
		WorkspaceFolderManager[] folders = new WorkspaceFolderManager[1];
		Workspace workspace = new Workspace(new WorkspaceSettings("test")) {
			@Override public File getWorkspaceFolder() { return directory.toFile(); }
			@Override public WorkspaceFolderManager getFolderManager() { return folders[0]; }
		};
		var constructor = WorkspaceFolderManager.class.getDeclaredConstructor(File.class, Workspace.class);
		constructor.setAccessible(true);
		folders[0] = constructor.newInstance(directory.resolve("test.mcreator").toFile(), workspace);
		return workspace;
	}
}
