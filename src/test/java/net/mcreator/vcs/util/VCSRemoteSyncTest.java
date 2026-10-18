package net.mcreator.vcs.util;

import net.mcreator.vcs.workspace.VCSInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class VCSRemoteSyncTest {

	@TempDir Path directory;
	private final VCSInfo info = new VCSInfo("", "", null, true);

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cleanWorkspaceFetchesAndMergesRemoteCommits(boolean diverged) throws Exception {
		try (Git server = server(); Git client = cloneRepository(server)) {
			RevCommit remote = commit(server, "remote.txt", "remote work");
			RevCommit local = diverged ? commit(client, "local.txt", "local work") : head(client);
			assertTrue(client.status().call().isClean());
			AtomicBoolean processed = new AtomicBoolean();

			var result = VCSCommits.fetchAndMerge(client, info, null, null, (git, conflicts, handles, dryRun) -> {
				assertFalse(conflicts);
				assertFalse(dryRun);
				assertEquals("remote work", Files.readString(path(git, "remote.txt")));
				processed.set(true);
				return false;
			});

			assertNotNull(result);
			assertTrue(processed.get());
			assertFalse(result.requiredCustomMergeHandler());
			assertEquals(remote, server.getRepository().resolve("HEAD"));
			assertTrue(client.status().call().isClean());
			assertEquals(RepositoryState.SAFE, client.getRepository().getRepositoryState());
			if (diverged) {
				assertEquals(2, head(client).getParentCount());
				assertEquals(local, head(client).getParent(0));
				assertEquals(remote, head(client).getParent(1));
				assertEquals("local work", Files.readString(path(client, "local.txt")));
			} else {
				assertEquals(remote, client.getRepository().resolve("HEAD"));
			}
		}
	}

	@Test void repeatedSyncDoesNotCreateCommitsOrReprocessIntegratedRemote() throws Exception {
		try (Git server = server(); Git client = cloneRepository(server)) {
			RevCommit before = head(client);
			for (int i = 0; i < 2; i++) {
				assertNotNull(VCSCommits.fetchAndMerge(client, info, null, null,
						(git, conflicts, handles, dryRun) -> { fail("Already integrated remote"); return false; }));
				assertEquals(before, client.getRepository().resolve("HEAD"));
				assertTrue(client.status().call().isClean());
			}
		}
	}

	@Test void failedFetchPreservesLocalCommitAndFiles() throws Exception {
		try (Git server = server(); Git client = cloneRepository(server)) {
			RevCommit local = commit(client, "local.txt", "keep local work");
			client.getRepository().getConfig().setString("remote", "origin", "url",
					directory.resolve("missing").toUri().toString());

			assertThrows(Exception.class, () -> VCSCommits.fetchAndMerge(client, info, null, null,
					(git, conflicts, handles, dryRun) -> { fail("Fetch failed"); return false; }));

			assertEquals(local, client.getRepository().resolve("HEAD"));
			assertEquals("keep local work", Files.readString(path(client, "local.txt")));
			assertTrue(client.status().call().isClean());
		}
	}

	private Git server() throws Exception {
		Git git = Git.init().setInitialBranch("local").setDirectory(directory.resolve("server").toFile()).call();
		commit(git, "base.txt", "base");
		return git;
	}

	private Git cloneRepository(Git server) throws Exception {
		return Git.cloneRepository().setURI(server.getRepository().getWorkTree().toURI().toString())
				.setDirectory(directory.resolve("client").toFile()).call();
	}

	private RevCommit commit(Git git, String file, String content) throws Exception {
		Files.writeString(path(git, file), content);
		git.add().addFilepattern(".").call();
		return VCSCommits.commit(git, info).setMessage(file).call();
	}

	private Path path(Git git, String file) {
		return git.getRepository().getWorkTree().toPath().resolve(file);
	}

	private RevCommit head(Git git) throws Exception {
		return git.log().setMaxCount(1).call().iterator().next();
	}
}
