package net.mcreator.vcs.ui.component;

import net.mcreator.vcs.SwingTestSupport;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class BranchesPopupTest extends SwingTestSupport {

	@TempDir Path directory;

	@Test void pushingNewBranchRunsOffSwingAndKeepsModalInterfaceResponsive() throws Exception {
		try (Git server = Git.init().setBare(true).setDirectory(directory.resolve("server").toFile()).call();
				Git client = Git.init().setInitialBranch("feature").setDirectory(directory.resolve("client").toFile()).call()) {
			Path file = client.getRepository().getWorkTree().toPath().resolve("workspace.txt");
			Files.writeString(file, "keep local work");
			client.add().addFilepattern(".").call();
			var head = client.commit().setMessage("local").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
			client.getRepository().getConfig().setString("remote", "origin", "url",
					server.getRepository().getDirectory().toURI().toString());
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			Git slow = new Git(client.getRepository()) {
				@Override public PushCommand push() {
					assertFalse(SwingUtilities.isEventDispatchThread());
					started.countDown();
					try {
						if (!release.await(5, TimeUnit.SECONDS))
							throw new IllegalStateException("Test release timed out");
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(ex);
					}
					return super.push();
				}
			};
			CompletableFuture<Void> finished = CompletableFuture.runAsync(() -> {
				try {
					onSwingThread(() -> { BranchesPopup.pushBranch(slow, "feature", null, null); return null; });
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			});
			try {
				assertTrue(started.await(3, TimeUnit.SECONDS));
				CompletableFuture<Boolean> heartbeat = new CompletableFuture<>();
				SwingUtilities.invokeLater(() -> heartbeat.complete(true));
				assertTrue(heartbeat.get(3, TimeUnit.SECONDS));
				release.countDown();
				finished.get(5, TimeUnit.SECONDS);
				assertEquals(head, server.getRepository().resolve("refs/heads/feature"));
				assertEquals(head, client.getRepository().resolve("HEAD"));
				assertEquals("keep local work", Files.readString(file));
			} finally {
				release.countDown();
			}
		}
	}
}
