package net.mcreator.vcs.util;

import com.google.gson.Gson;
import net.mcreator.vcs.workspace.VCSInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VCSCommitsTest {

	@TempDir Path directory;

	private final VCSInfo info = new VCSInfo("git@example.invalid:owner/repo.git", "", null, true);

	@Test void pushChecksSuccessfulAndUpToDateResults() throws Exception {
		try (Git server = Git.init().setBare(true).setDirectory(directory.resolve("server").toFile()).call();
				Git client = Git.init().setInitialBranch("local")
						.setDirectory(directory.resolve("client").toFile()).call()) {
			Files.writeString(client.getRepository().getWorkTree().toPath().resolve("workspace.txt"), "local work");
			client.add().addFilepattern(".").call();
			RevCommit local = VCSCommits.commit(client, info).setMessage("local").call();
			for (int i = 0; i < 2; i++) {
				VCSCommits.checkPushResults(client.push().setRemote(server.getRepository().getDirectory().toURI().toString())
						.add("local").call());
				assertEquals(local, server.getRepository().resolve("refs/heads/local"));
				assertTrue(client.status().call().isClean());
			}
		}
	}

	@Test void rejectedPushReportsFailureAndPreservesBothRepositories() throws Exception {
		try (Git server = Git.init().setBare(true).setDirectory(directory.resolve("server").toFile()).call();
				Git client = Git.init().setInitialBranch("local")
						.setDirectory(directory.resolve("client").toFile()).call()) {
			Path file = client.getRepository().getWorkTree().toPath().resolve("workspace.txt");
			Files.writeString(file, "base");
			client.add().addFilepattern(".").call();
			RevCommit base = VCSCommits.commit(client, info).setMessage("base").call();
			String remote = server.getRepository().getDirectory().toURI().toString();
			VCSCommits.checkPushResults(client.push().setRemote(remote).add("local").call());
			Files.writeString(file, "remote work");
			client.add().addFilepattern(".").call();
			RevCommit remoteCommit = VCSCommits.commit(client, info).setMessage("remote").call();
			VCSCommits.checkPushResults(client.push().setRemote(remote).add("local").call());
			client.checkout().setCreateBranch(true).setName("diverged").setStartPoint(base).call();
			Files.writeString(file, "keep local work");
			client.add().addFilepattern(".").call();
			RevCommit local = VCSCommits.commit(client, info).setMessage("local").call();

			IOException failure = assertThrows(IOException.class, () -> VCSCommits.checkPushResults(
					client.push().setRemote(remote).setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
							"refs/heads/diverged:refs/heads/local")).call()));

			assertTrue(failure.getMessage().contains("REJECTED_NONFASTFORWARD"));
			assertEquals(remoteCommit, server.getRepository().resolve("refs/heads/local"));
			assertEquals(local, client.getRepository().resolve("HEAD"));
			assertEquals("keep local work", Files.readString(file));
			assertTrue(client.status().call().isClean());
		}
	}

	@Test void ordinaryAndCleanupCommitsOverrideBothIdentitiesWithoutChangingGitConfig() throws Exception {
		try (Git git = repository()) {
			byte[] config = Files.readAllBytes(directory.resolve(".git/config"));
			RevCommit initial = commit(git, "workspace.txt", "first", "local changes");
			RevCommit cleanup = commit(git, "workspace.txt", "second", "MCreator cleanup");
			assertIdentity(initial);
			assertIdentity(cleanup);
			assertEquals(initial, cleanup.getParent(0));
			assertArrayEquals(config, Files.readAllBytes(directory.resolve(".git/config")));
		}
	}

	@Test void automaticMergeUsesPublicIdentityAndPreservesBothSides() throws Exception {
		try (Git git = repository()) {
			commit(git, "base.txt", "base", "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			RevCommit remote = commit(git, "remote.txt", "remote work", "remote");
			git.checkout().setName("local").call();
			RevCommit local = commit(git, "local.txt", "local work", "local");
			byte[] config = Files.readAllBytes(directory.resolve(".git/config"));

			VCSCommits.merge(git, git.merge().include(remote).setMessage("automatic merge"), info);

			RevCommit merged = head(git);
			assertIdentity(merged);
			assertEquals("automatic merge", merged.getFullMessage());
			assertEquals(2, merged.getParentCount());
			assertEquals(local, merged.getParent(0));
			assertEquals(remote, merged.getParent(1));
			assertEquals("local work", Files.readString(directory.resolve("local.txt")));
			assertEquals("remote work", Files.readString(directory.resolve("remote.txt")));
			assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState());
			assertArrayEquals(config, Files.readAllBytes(directory.resolve(".git/config")));
		}
	}

	@Test void resolvedConflictCommitKeepsMergeParentsAndSelectedContent() throws Exception {
		try (Git git = repository()) {
			commit(git, "workspace.txt", "base\n", "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			RevCommit remote = commit(git, "workspace.txt", "remote\n", "remote");
			git.checkout().setName("local").call();
			RevCommit local = commit(git, "workspace.txt", "local\n", "local");

			VCSCommits.merge(git, git.merge().include(remote), info);
			assertEquals(local, git.getRepository().resolve("HEAD"));
			assertEquals(RepositoryState.MERGING, git.getRepository().getRepositoryState());
			RevCommit resolved = commit(git, "workspace.txt", "selected resolution\n", "resolve conflict");

			assertIdentity(resolved);
			assertEquals(2, resolved.getParentCount());
			assertEquals(local, resolved.getParent(0));
			assertEquals(remote, resolved.getParent(1));
			assertEquals("selected resolution\n", Files.readString(directory.resolve("workspace.txt")));
			assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState());
		}
	}

	@Test void fastForwardAndAlreadyIntegratedRemotePreserveExistingCommitIdentity() throws Exception {
		try (Git git = repository()) {
			commit(git, "workspace.txt", "base", "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			Files.writeString(directory.resolve("workspace.txt"), "remote");
			git.add().addFilepattern(".").call();
			RevCommit remote = git.commit().setMessage("someone else's work")
					.setAuthor("Collaborator", "collaborator@example.com")
					.setCommitter("Collaborator", "collaborator@example.com").call();
			git.checkout().setName("local").call();

			for (int i = 0; i < 2; i++) {
				VCSCommits.merge(git, git.merge().include(remote), info);
				assertEquals(remote, git.getRepository().resolve("HEAD"));
				assertEquals("Collaborator", head(git).getAuthorIdent().getName());
				assertEquals("Collaborator", head(git).getCommitterIdent().getName());
				assertEquals("remote", Files.readString(directory.resolve("workspace.txt")));
			}
		}
	}

	@Test void historyJumpUsesPublicIdentityAndRestoresSelectedTree() throws Exception {
		try (Git git = repository()) {
			RevCommit selected = commit(git, "workspace.txt", "selected state", "selected");
			RevCommit previous = commit(git, "workspace.txt", "new state", "new");
			git.checkout().setCreateBranch(true).setName("history").setStartPoint(selected).call();

			VCSCommits.merge(git, git.merge().setStrategy(MergeStrategy.OURS).include(previous)
					.setFastForward(MergeCommand.FastForwardMode.NO_FF).setMessage("Jump back"), info);
			RevCommit jump = head(git);
			assertIdentity(jump);
			assertEquals(selected.getTree(), jump.getTree());
			assertEquals(2, jump.getParentCount());
			git.checkout().setName("local").call();
			VCSCommits.merge(git, git.merge().include(jump), info);
			assertEquals(jump, git.getRepository().resolve("HEAD"));
			assertEquals("selected state", Files.readString(directory.resolve("workspace.txt")));
		}
	}

	@Test void fetchedBranchSelectionHonorsTrackingAndDoesNotMergeAnotherRemoteBranch() throws Exception {
		Path remoteDirectory = directory.resolve("server");
		Path localDirectory = directory.resolve("client");
		try (Git server = Git.init().setDirectory(remoteDirectory.toFile()).setInitialBranch("stable").call()) {
			Files.writeString(remoteDirectory.resolve("workspace.txt"), "stable");
			server.add().addFilepattern(".").call();
			RevCommit stable = VCSCommits.commit(server, info).setMessage("stable").call();
			server.checkout().setCreateBranch(true).setName("development").call();
			Files.writeString(remoteDirectory.resolve("workspace.txt"), "development");
			server.add().addFilepattern(".").call();
			RevCommit development = VCSCommits.commit(server, info).setMessage("development").call();
			server.checkout().setName("stable").call();
			try (Git client = Git.cloneRepository().setURI(remoteDirectory.toUri().toString())
					.setDirectory(localDirectory.toFile()).call()) {
				var fetch = client.fetch().setRemote("origin").call();
				assertEquals(stable, VCSCommits.remoteHead(client, fetch));
				client.getRepository().getConfig().setString("branch", "stable", "merge", "refs/heads/development");
				assertEquals(development, VCSCommits.remoteHead(client, fetch));
				VCSCommits.merge(client, client.merge().include(VCSCommits.remoteHead(client, fetch)), info);
				assertEquals("development", Files.readString(localDirectory.resolve("workspace.txt")));
				client.getRepository().getConfig().unset("branch", "stable", "merge");
				assertEquals(stable, VCSCommits.remoteHead(client, fetch));
				client.getRepository().getConfig().setString("branch", "stable", "merge", "refs/heads/missing");
				assertNull(VCSCommits.remoteHead(client, fetch));
				assertEquals(development, client.getRepository().resolve("HEAD"));
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "true", "false", "only" })
	void remoteMergeRespectsPullFastForwardPolicyAndPreservesDataOnRefusal(String policy) throws Exception {
		try (Git git = repository()) {
			RevCommit base = commit(git, "base.txt", "base", "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			RevCommit remote = commit(git, "remote.txt", "remote work", "remote");
			git.checkout().setName("local").call();
			RevCommit local = base;
			if (policy.equals("only"))
				local = commit(git, "local.txt", "local work", "local");
			else
				Files.writeString(directory.resolve("local.txt"), "local work");
			git.getRepository().getConfig().setString("pull", null, "ff", policy);
			if (policy.equals("only")) {
				assertThrows(IOException.class, () -> VCSCommits.mergeRemote(git, remote, info, null));
				assertEquals(local, git.getRepository().resolve("HEAD"));
				assertFalse(Files.exists(directory.resolve("remote.txt")));
			} else {
				VCSCommits.mergeRemote(git, remote, info, null);
				assertIdentity(head(git));
				if (policy.equals("false"))
					assertEquals(2, head(git).getParentCount());
				else
					assertEquals(remote, git.getRepository().resolve("HEAD"));
				assertEquals("remote work", Files.readString(directory.resolve("remote.txt")));
			}
			assertEquals("local work", Files.readString(directory.resolve("local.txt")));
			assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState());
		}
	}

	@Test void stashingAndRestoringUncommittedWorkPreservesContentWithPublicIdentity() throws Exception {
		try (Git git = repository()) {
			RevCommit base = commit(git, "workspace.txt", "base", "base");
			Files.writeString(directory.resolve("workspace.txt"), "unsaved work");
			Files.writeString(directory.resolve("new.txt"), "new work");
			RevCommit stash = git.stashCreate().setPerson(info.getCommitIdentity()).setIncludeUntracked(true).call();
			assertNotNull(stash);
			assertIdentity(stash);
			assertEquals(base, git.getRepository().resolve("HEAD"));
			git.stashApply().setStashRef(stash.getName()).call();
			assertEquals("unsaved work", Files.readString(directory.resolve("workspace.txt")));
			assertEquals("new work", Files.readString(directory.resolve("new.txt")));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "account-alias", "" })
	void legacySettingsUseAccountAliasOrNeutralNameAndNeverMachineEmail(String username) {
		VCSInfo legacy = new Gson().fromJson("{\"username\":\"" + username + "\"}", VCSInfo.class);
		assertEquals(username.isBlank() ? "MCreator" : username, legacy.getCommitIdentity().getName());
		assertEquals("mcreator@localhost", legacy.getCommitIdentity().getEmailAddress());
	}

	@Test void explicitIdentitySurvivesPersistenceAndBlankFieldsSelectNeutralIdentity() {
		info.setCommitIdentity("Public alias", "123+alias@users.noreply.github.com");
		Path file = directory.resolve("vcsInfo");
		VCSInfo.saveToFile(info, file.toFile());
		VCSInfo restored = VCSInfo.loadFromFile(file.toFile());
		assertNotNull(restored);
		assertEquals(info.getCommitName(), restored.getCommitName());
		assertEquals(info.getCommitEmail(), restored.getCommitEmail());
		assertEquals("", restored.getUsername());
		restored.setCommitIdentity(" ", " ");
		assertEquals("MCreator", restored.getCommitIdentity().getName());
		assertEquals("mcreator@localhost", restored.getCommitIdentity().getEmailAddress());
	}

	private Git repository() throws Exception {
		info.setCommitIdentity("Public alias", "123+alias@users.noreply.github.com");
		Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("local").call();
		git.getRepository().getConfig().setString("user", null, "name", "Private Windows user");
		git.getRepository().getConfig().setString("user", null, "email", "private-user@private-computer");
		git.getRepository().getConfig().save();
		return git;
	}

	private RevCommit commit(Git git, String file, String content, String message) throws Exception {
		Files.writeString(directory.resolve(file), content);
		git.add().addFilepattern(".").call();
		return VCSCommits.commit(git, info).setAll(true).setAllowEmpty(false).setMessage(message).call();
	}

	private RevCommit head(Git git) throws Exception {
		return git.log().setMaxCount(1).call().iterator().next();
	}

	private void assertIdentity(RevCommit commit) {
		PersonIdent author = commit.getAuthorIdent();
		PersonIdent committer = commit.getCommitterIdent();
		assertEquals("Public alias", author.getName());
		assertEquals("123+alias@users.noreply.github.com", author.getEmailAddress());
		assertEquals(author, committer);
		assertFalse(commit.getFullMessage().contains("Private Windows user"));
	}

}
