package net.mcreator.vcs.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VCSBranchesTest {
	@TempDir Path directory;

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
