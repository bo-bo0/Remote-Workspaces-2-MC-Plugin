package net.mcreator.vcs.util;

import net.mcreator.vcs.SwingTestSupport;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class VCSHistoryTest extends SwingTestSupport {

	@TempDir Path directory;

	@Test void pagesAreBoundedAndPinnedToTheOriginalHead() throws Exception {
		try (Git git = repository()) {
			List<ObjectId> expected = new ArrayList<>();
			for (int i = 0; i < 7; i++)
				expected.addFirst(commit(git, "commit " + i));
			ObjectId head = git.getRepository().resolve("HEAD");
			List<ObjectId> loaded = new ArrayList<>();
			for (int offset = 0; offset < 7; offset += 3) {
				VCSHistory.Page page = VCSHistory.readPage(git, head, offset, 3);
				assertEquals(Math.min(3, 7 - offset), page.commits().size());
				assertEquals(offset + 3 < 7, page.hasMore());
				assertEquals(head, page.head());
				page.commits().forEach(value -> loaded.add(value.getId()));
				if (offset == 0)
					commit(git, "arrived while browsing");
			}
			assertEquals(expected, loaded);
			VCSHistory.Page refreshed = VCSHistory.readPage(git, null, 0, 3);
			assertEquals("arrived while browsing", refreshed.commits().getFirst().getShortMessage());
			assertTrue(git.status().call().isClean());
		}
	}

	@Test void emptyRepositoryAndEndOfHistoryReturnEmptyPages() throws Exception {
		try (Git git = repository()) {
			assertTrue(VCSHistory.readPage(git, null, 0, 3).commits().isEmpty());
			assertFalse(VCSHistory.readPage(git, null, 0, 3).hasMore());
			commit(git, "one");
			assertTrue(VCSHistory.readPage(git, null, 3, 3).commits().isEmpty());
			assertThrows(IllegalArgumentException.class, () -> VCSHistory.readPage(git, null, -1, 3));
		}
	}

	@Test void selectedCommitMustStillBelongToTheCurrentBranch() throws Exception {
		try (Git git = repository()) {
			RevCommit base = commit(git, "base");
			RevCommit selected = commit(git, "selected");
			assertTrue(VCSHistory.isInCurrentHistory(git, base));
			assertTrue(VCSHistory.isInCurrentHistory(git, selected));
			git.checkout().setCreateBranch(true).setName("other").setStartPoint(base).call();
			RevCommit other = commit(git, "other branch");
			assertFalse(VCSHistory.isInCurrentHistory(git, selected));
			assertTrue(VCSHistory.isInCurrentHistory(git, base));
			assertEquals(other, git.getRepository().resolve("HEAD"));
			assertEquals("other branch", Files.readString(directory.resolve("workspace.txt")));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void slowHistoryReadsKeepSwingResponsiveAndDeliverResultsOnSwing(boolean fail) throws Exception {
		try (Git git = repository()) {
			commit(git, "one");
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			CompletableFuture<Object> completed = new CompletableFuture<>();
			Git slow = slowGit(git, started, release, fail);
			var worker = onSwingThread(() -> VCSHistory.loadPage(slow, null, 0, 3, page -> {
				assertTrue(SwingUtilities.isEventDispatchThread());
				completed.complete(page);
			}, ex -> {
				assertTrue(SwingUtilities.isEventDispatchThread());
				completed.complete(ex);
			}));
			try {
				assertTrue(started.await(3, TimeUnit.SECONDS));
				CompletableFuture<Boolean> heartbeat = new CompletableFuture<>();
				SwingUtilities.invokeLater(() -> heartbeat.complete(true));
				assertTrue(heartbeat.get(3, TimeUnit.SECONDS));
				release.countDown();
				if (fail)
					assertInstanceOf(IllegalStateException.class, completed.get(3, TimeUnit.SECONDS));
				else
					assertEquals(1, ((VCSHistory.Page) completed.get(3, TimeUnit.SECONDS)).commits().size());
			} finally {
				release.countDown();
				worker.cancel(true);
			}
		}
	}

	@Test void cancelledReadDoesNotPublishObsoleteResults() throws Exception {
		try (Git git = repository()) {
			commit(git, "one");
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			CountDownLatch callback = new CountDownLatch(1);
			var worker = onSwingThread(() -> VCSHistory.loadPage(slowGit(git, started, release, false), null, 0, 3,
					page -> callback.countDown(), ex -> callback.countDown()));
			try {
				assertTrue(started.await(3, TimeUnit.SECONDS));
				assertTrue(worker.cancel(true));
				release.countDown();
				assertThrows(CancellationException.class, worker::get);
				assertFalse(callback.await(300, TimeUnit.MILLISECONDS));
			} finally {
				release.countDown();
				worker.cancel(true);
			}
		}
	}

	private Git slowGit(Git git, CountDownLatch started, CountDownLatch release, boolean fail) {
		return new Git(git.getRepository()) {
			@Override public LogCommand log() {
				assertFalse(SwingUtilities.isEventDispatchThread());
				started.countDown();
				try {
					if (!release.await(5, TimeUnit.SECONDS))
						throw new IllegalStateException("Test release timed out");
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				if (fail)
					throw new IllegalStateException("History read failed");
				return super.log();
			}
		};
	}

	private Git repository() throws Exception {
		return Git.init().setInitialBranch("local").setDirectory(directory.toFile()).call();
	}

	private RevCommit commit(Git git, String message) throws Exception {
		Files.writeString(directory.resolve("workspace.txt"), message);
		git.add().addFilepattern(".").call();
		return git.commit().setMessage(message).setAuthor("Test", "test@example.com")
				.setCommitter("Test", "test@example.com").call();
	}
}
