package net.mcreator.vcs.workspace;

import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.WorkspaceFolderManager;
import net.mcreator.workspace.settings.WorkspaceSettings;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceVCSLifecycleTest {

	@TempDir Path directory;

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
