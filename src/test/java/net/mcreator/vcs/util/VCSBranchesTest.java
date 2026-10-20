package net.mcreator.vcs.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VCSBranchesTest {
	@TempDir Path directory;

	@Test void checkoutUsesSelectedRemoteRefAndKeepsCompleteBranchName() throws Exception {
		try (Git git = repository()) {
			RevCommit selected = commit(git, "selected");
			RevCommit unrelated = commit(git, "unrelated");
			git.branchCreate().setName("test").call();
			var config = git.getRepository().getConfig();
			config.setString("remote", "origin", "url", "file:///unused");
			config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
			var remote = git.getRepository().updateRef("refs/remotes/origin/feature/test");
			remote.setNewObjectId(selected);
			remote.update();
			var fetchHead = git.getRepository().updateRef("FETCH_HEAD");
			fetchHead.setNewObjectId(unrelated);
			fetchHead.update();
			VCSBranches.checkout(git, "refs/remotes/origin/feature/test");
			assertEquals("feature/test", git.getRepository().getBranch());
			assertEquals(selected, git.getRepository().resolve("HEAD"));
			assertEquals(unrelated, git.getRepository().resolve("refs/heads/test"));
			assertEquals("origin", config.getString("branch", "feature/test", "remote"));
			assertEquals("refs/heads/feature/test", config.getString("branch", "feature/test", "merge"));
		}
	}

	@Test void failedCheckoutPreservesUncommittedWorkAndCurrentBranch() throws Exception {
		try (Git git = repository()) {
			RevCommit base = commit(git, "base");
			git.branchCreate().setName("feature/test").call();
			RevCommit local = commit(git, "local");
			Files.writeString(directory.resolve("workspace.txt"), "unsaved work");
			byte[] index = Files.readAllBytes(directory.resolve(".git/index"));
			assertThrows(Exception.class, () -> VCSBranches.checkout(git, "refs/heads/missing"));
			assertThrows(Exception.class, () -> VCSBranches.checkout(git, "refs/heads/feature/test"));
			assertEquals("local", git.getRepository().getBranch());
			assertEquals(local, git.getRepository().resolve("HEAD"));
			assertEquals(base, git.getRepository().resolve("refs/heads/feature/test"));
			assertEquals("unsaved work", Files.readString(directory.resolve("workspace.txt")));
			assertArrayEquals(index, Files.readAllBytes(directory.resolve(".git/index")));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "origin", "team/server" })
	void remoteDeletionUsesCompleteBranchAndSelectedRemote(String remoteName) throws Exception {
		try (Git git = repository(); Git server = Git.init().setBare(true)
				.setDirectory(directory.resolve("server").toFile()).call()) {
			RevCommit head = commit(git, "base");
			git.remoteAdd().setName(remoteName).setUri(new URIish(server.getRepository().getDirectory().toURI().toString())).call();
			VCSCommits.checkPushResults(git.push().setRemote(remoteName).setRefSpecs(
					new RefSpec("refs/heads/local:refs/heads/feature/test"),
					new RefSpec("refs/heads/local:refs/heads/test")).call());
			git.fetch().setRemote(remoteName).call();
			Files.writeString(directory.resolve("workspace.txt"), "keep local work");
			VCSBranches.delete(git, git.getRepository().exactRef("refs/remotes/" + remoteName + "/feature/test"), null, null);
			assertNull(server.getRepository().exactRef("refs/heads/feature/test"));
			assertEquals(head, server.getRepository().resolve("refs/heads/test"));
			assertNull(git.getRepository().exactRef("refs/remotes/" + remoteName + "/feature/test"));
			assertEquals(head, git.getRepository().resolve("HEAD"));
			assertEquals("keep local work", Files.readString(directory.resolve("workspace.txt")));
		}
	}

	@Test void deletionPreservesActiveBranchFilesAndIndexOnSuccessAndFailure() throws Exception {
		try (Git git = repository()) {
			RevCommit head = commit(git, "base");
			git.branchCreate().setName("other").call();
			Files.writeString(directory.resolve("workspace.txt"), "staged work");
			git.add().addFilepattern(".").call();
			Files.writeString(directory.resolve("workspace.txt"), "unstaged work");
			Files.writeString(directory.resolve("new.txt"), "untracked work");
			byte[] index = Files.readAllBytes(directory.resolve(".git/index"));
			VCSBranches.deleteLocal(git, "refs/heads/other");
			assertNull(git.getRepository().exactRef("refs/heads/other"));
			assertThrows(Exception.class, () -> VCSBranches.deleteLocal(git, "refs/heads/local"));
			assertEquals(head, git.getRepository().resolve("HEAD"));
			assertEquals("unstaged work", Files.readString(directory.resolve("workspace.txt")));
			assertEquals("untracked work", Files.readString(directory.resolve("new.txt")));
			assertArrayEquals(index, Files.readAllBytes(directory.resolve(".git/index")));
		}
	}

	private Git repository() throws Exception {
		return Git.init().setInitialBranch("local").setDirectory(directory.toFile()).call();
	}

	private RevCommit commit(Git git, String text) throws Exception {
		Files.writeString(directory.resolve("workspace.txt"), text);
		git.add().addFilepattern(".").call();
		return git.commit().setMessage(text).setAuthor("Test", "test@example.com")
				.setCommitter("Test", "test@example.com").call();
	}
}
