package net.mcreator.vcs.util;

import net.mcreator.vcs.util.diff.ResultSide;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SyncTwoRefsWithMergeTest {

	@TempDir Path repository;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void modifyDeleteConflictPreservesBothVersionsAndDryRunDoesNotChangeRepository(boolean deleteRemote)
			throws Exception {
		try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("local").call()) {
			Path file = repository.resolve("conflict.txt");
			Files.writeString(file, "base\n");
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			if (deleteRemote)
				git.rm().addFilepattern("conflict.txt").call();
			else
				Files.writeString(file, "remote\n");
			RevCommit remote = commit(git, "remote change");
			git.checkout().setName("local").call();
			if (deleteRemote)
				Files.writeString(file, "local\n");
			else
				git.rm().addFilepattern("conflict.txt").call();
			RevCommit local = commit(git, "local change");
			AtomicBoolean handlerCalled = new AtomicBoolean();

			SyncTwoRefsWithMerge.SyncResult result = SyncTwoRefsWithMerge.sync(git, local, remote,
					(repositoryGit, conflicts, handles, dryRun) -> {
						handlerCalled.set(true);
						assertTrue(conflicts);
						assertTrue(dryRun);
						assertEquals(1, handles.size());
						FileSyncHandle handle = handles.getFirst();
						assertTrue(handle.isUnmerged());
						assertArrayEquals("base\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
								handle.getBaseBytes());
						assertEquals(deleteRemote ? DiffEntry.ChangeType.MODIFY : DiffEntry.ChangeType.DELETE,
								handle.getChangeTypeRelativeToLocal());
						assertEquals(deleteRemote ? DiffEntry.ChangeType.DELETE : DiffEntry.ChangeType.MODIFY,
								handle.getChangeTypeRelativeToRemote());
						var merge = handle.toPathMergeHandle();
						merge.selectResultSide(ResultSide.REMOTE);
						assertEquals(handle.getChangeTypeRelativeToRemote(), merge.getSelectedResultChangeType());
						if (deleteRemote)
							assertNull(handle.getRemoteBytes());
						else
							assertArrayEquals("remote\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
									handle.getRemoteBytes());
						return true;
					}, null, true);

			assertTrue(handlerCalled.get());
			assertTrue(result.requiredCustomMergeHandler());
			assertTrue(result.requiredUserAction());
			assertEquals(local, git.getRepository().resolve(Constants.HEAD));
			assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState());
			assertTrue(git.status().call().isClean());
			if (deleteRemote)
				assertEquals("local\n", Files.readString(file));
			else
				assertFalse(Files.exists(file));
		}
	}

	private RevCommit commit(Git git, String message) throws Exception {
		git.add().addFilepattern(".").call();
		return git.commit().setMessage(message).setAuthor("Test", "test@example.com")
				.setCommitter("Test", "test@example.com").call();
	}
}
