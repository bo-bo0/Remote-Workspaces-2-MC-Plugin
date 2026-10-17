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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SyncTwoRefsWithMergeTest {

	@TempDir Path repository;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void alreadyIntegratedRemoteSkipsWorkspaceProcessingAndPreservesLocalFiles(boolean dryRun) throws Exception {
		try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("local").call()) {
			Path file = repository.resolve("workspace.mcreator");
			Files.writeString(file, "base\n");
			RevCommit remote = commit(git, "base");

			for (int i = 0; i < 2; i++) {
				RevCommit local = git.log().setMaxCount(1).call().iterator().next();
				Files.writeString(repository.resolve("uncommitted.txt"), "keep local work\n");
				SyncTwoRefsWithMerge.SyncResult result = SyncTwoRefsWithMerge.sync(git, local, remote,
						(repositoryGit, conflicts, handles, testOnly) -> {
							fail("An already integrated remote must not reload or regenerate the workspace");
							return true;
						}, () -> fail("An already integrated remote must not be pulled again"), dryRun);

				assertFalse(result.requiredCustomMergeHandler());
				assertFalse(result.requiredUserAction());
				assertEquals(local, git.getRepository().resolve(Constants.HEAD));
				assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState());
				assertEquals(i == 0 ? "base\n" : "local\n", Files.readString(file));
				assertEquals("keep local work\n", Files.readString(repository.resolve("uncommitted.txt")));
				Files.writeString(file, "local\n");
				commit(git, "local change");
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void oneSidedChangesRetainUnchangedContentAndDoNotBecomeDeletions(boolean changeRemote) throws Exception {
		try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("local").call()) {
			Files.writeString(repository.resolve("workspace.mcreator"), "base\n");
			Files.writeString(repository.resolve("deleted.txt"), "base\n");
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			changeOneSide(git, changeRemote);
			RevCommit remote = commit(git, "remote change");
			git.checkout().setName("local").call();
			changeOneSide(git, !changeRemote);
			RevCommit local = commit(git, "local change");
			AtomicBoolean handlerCalled = new AtomicBoolean();

			SyncTwoRefsWithMerge.SyncResult result = SyncTwoRefsWithMerge.sync(git, local, remote,
					(repositoryGit, conflicts, handles, dryRun) -> {
						handlerCalled.set(true);
						assertFalse(conflicts);
						Map<String, FileSyncHandle> byPath = handles.stream()
								.collect(Collectors.toMap(FileSyncHandle::getBasePath, handle -> handle));
						assertEquals(4, byPath.size());
						FileSyncHandle workspace = byPath.get("workspace.mcreator");
						assertArrayEquals("base\n".getBytes(StandardCharsets.UTF_8), workspace.getBaseBytes());
						assertArrayEquals((changeRemote ? "base\n" : "changed\n").getBytes(StandardCharsets.UTF_8),
								workspace.getLocalBytes());
						assertArrayEquals((changeRemote ? "changed\n" : "base\n").getBytes(StandardCharsets.UTF_8),
								workspace.getRemoteBytes());
						assertEquals("workspace.mcreator", workspace.getLocalPath());
						assertEquals("workspace.mcreator", workspace.getRemotePath());
						FileSyncHandle deleted = byPath.get("deleted.txt");
						FileSyncHandle added = byPath.get("added.txt");
						ResultSide changed = changeRemote ? ResultSide.REMOTE : ResultSide.LOCAL;
						ResultSide unchanged = changeRemote ? ResultSide.LOCAL : ResultSide.REMOTE;
						assertNull(deleted.getBytes(changed));
						assertArrayEquals("base\n".getBytes(StandardCharsets.UTF_8), deleted.getBytes(unchanged));
						assertNull(added.getBytes(unchanged));
						assertArrayEquals("added\n".getBytes(StandardCharsets.UTF_8), added.getBytes(changed));
						assertTrue(handles.stream().noneMatch(FileSyncHandle::isUnmerged));
						return false;
					}, null, true);

			assertTrue(handlerCalled.get());
			assertFalse(result.requiredCustomMergeHandler());
			assertFalse(result.requiredUserAction());
			assertEquals(local, git.getRepository().resolve(Constants.HEAD));
			assertTrue(git.status().call().isClean());
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void largeMergePairsBothSidesByPathAndPreservesConflictFlags(boolean dryRun) throws Exception {
		try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("local").call()) {
			for (int i = 0; i < 1500; i++)
				Files.writeString(repository.resolve("element" + i + ".json"), "base " + i + "\n");
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			for (int i = 0; i < 1500; i++)
				Files.writeString(repository.resolve("element" + i + ".json"), "remote " + i + "\n");
			RevCommit remote = commit(git, "remote change");
			git.checkout().setName("local").call();
			for (int i = 0; i < 1500; i++)
				Files.writeString(repository.resolve("element" + i + ".json"), "local " + i + "\n");
			RevCommit local = commit(git, "local change");
			AtomicInteger preMergeCalls = new AtomicInteger();
			AtomicBoolean handlerCalled = new AtomicBoolean();

			SyncTwoRefsWithMerge.SyncResult result = SyncTwoRefsWithMerge.sync(git, local, remote,
					(repositoryGit, conflicts, handles, testOnly) -> {
						handlerCalled.set(true);
						assertTrue(conflicts);
						assertEquals(dryRun, testOnly);
						assertEquals(1, preMergeCalls.get());
						Map<String, FileSyncHandle> byPath = handles.stream()
								.collect(Collectors.toMap(FileSyncHandle::getBasePath, handle -> handle));
						assertEquals(1500, byPath.size());
						for (int i = 0; i < 1500; i++) {
							FileSyncHandle handle = byPath.get("element" + i + ".json");
							assertTrue(handle.isUnmerged());
							assertArrayEquals(("base " + i + "\n").getBytes(StandardCharsets.UTF_8),
									handle.getBaseBytes());
							assertArrayEquals(("local " + i + "\n").getBytes(StandardCharsets.UTF_8),
									handle.getLocalBytes());
							assertArrayEquals(("remote " + i + "\n").getBytes(StandardCharsets.UTF_8),
									handle.getRemoteBytes());
						}
						return true;
					}, preMergeCalls::incrementAndGet, dryRun);

			assertTrue(handlerCalled.get());
			assertTrue(result.requiredCustomMergeHandler());
			assertTrue(result.requiredUserAction());
			assertEquals(local, git.getRepository().resolve(Constants.HEAD));
			assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState());
			assertTrue(git.status().call().isClean());
		}
	}

	private void changeOneSide(Git git, boolean changeWorkspace) throws Exception {
		if (changeWorkspace) {
			Files.writeString(repository.resolve("workspace.mcreator"), "changed\n");
			git.rm().addFilepattern("deleted.txt").call();
			Files.writeString(repository.resolve("added.txt"), "added\n");
		} else {
			Files.writeString(repository.resolve("other.txt"), "independent change\n");
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cleanRemoteUpdatesStillRunWorkspaceHandlerAfterMerge(boolean diverged) throws Exception {
		try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("local").call()) {
			Files.writeString(repository.resolve("workspace.mcreator"), "base\n");
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			Files.writeString(repository.resolve("workspace.mcreator"), "remote\n");
			RevCommit remote = commit(git, "remote change");
			git.checkout().setName("local").call();
			if (diverged) {
				Files.writeString(repository.resolve("local.txt"), "local\n");
				commit(git, "local change");
			}
			var config = git.getRepository().getConfig();
			config.setString("user", null, "name", "Test");
			config.setString("user", null, "email", "test@example.com");
			RevCommit local = git.log().setMaxCount(1).call().iterator().next();
			AtomicBoolean handlerCalled = new AtomicBoolean();

			SyncTwoRefsWithMerge.SyncResult result = SyncTwoRefsWithMerge.sync(git, local, remote,
					(repositoryGit, conflicts, handles, dryRun) -> {
						handlerCalled.set(true);
						assertFalse(conflicts);
						assertFalse(dryRun);
						assertEquals(List.of("remote"), Files.readAllLines(repository.resolve("workspace.mcreator")));
						return false;
					}, () -> assertTrue(git.merge().include(remote).call().getMergeStatus().isSuccessful()), false);

			assertTrue(handlerCalled.get());
			assertFalse(result.requiredCustomMergeHandler());
			assertFalse(result.requiredUserAction());
			assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState());
			assertTrue(git.status().call().isClean());
			assertEquals(diverged, Files.exists(repository.resolve("local.txt")));
		}
	}

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
