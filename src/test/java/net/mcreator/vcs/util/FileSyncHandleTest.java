package net.mcreator.vcs.util;

import net.mcreator.vcs.util.diff.MergeHandle;
import net.mcreator.vcs.util.diff.ResultSide;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileSyncHandleTest {

	@TempDir Path directory;

	@Test void classifyingManyLazyVersionsDoesNotLoadAnyContent() {
		FileSyncHandle.ObjectSource source = () -> { throw new AssertionError("Content must stay lazy"); };
		for (int i = 0; i < 2000; i++) {
			FileSyncHandle handle = new FileSyncHandle("file" + i);
			handle.setBaseObject(source);
			handle.setLocalObject(source);
			assertEquals(DiffEntry.ChangeType.MODIFY, handle.getChangeTypeRelativeToLocal());
			assertEquals(DiffEntry.ChangeType.DELETE, handle.getChangeTypeRelativeToRemote());
			handle.setRemoteObject(source);
			assertEquals(DiffEntry.ChangeType.MODIFY, handle.toPathMergeHandle().getRemoteChange());
			assertTrue(handle.toString().endsWith("RLB"));
		}
	}

	@Test void largeSelectedFileIsStreamedWithoutMaterializingItsBytes() throws Exception {
		AtomicInteger streamsClosed = new AtomicInteger();
		FileSyncHandle handle = new FileSyncHandle("file.bin");
		handle.setRemoteObject(() -> streamingObject(false, streamsClosed));
		handle.setLocalObject(() -> { throw new AssertionError("Unselected version"); });
		Path destination = directory.resolve("file.bin");
		Files.writeString(destination, "previous content");
		handle.writeTo(destination, ResultSide.REMOTE);
		assertEquals(8L * 1024 * 1024, Files.size(destination));
		assertEquals(1, streamsClosed.get());
		try (InputStream input = Files.newInputStream(destination)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1; )
				for (int i = 0; i < count; i++)
					assertEquals(42, buffer[i]);
		}
		try (var files = Files.list(directory)) {
			assertEquals(1, files.count());
		}
	}

	@Test void interruptedStreamPreservesOriginalFileAndRemovesTemporaryContent() throws Exception {
		AtomicInteger streamsClosed = new AtomicInteger();
		FileSyncHandle handle = new FileSyncHandle("file.bin");
		handle.setRemoteObject(() -> streamingObject(true, streamsClosed));
		Path destination = directory.resolve("file.bin");
		Files.writeString(destination, "keep original content");
		assertThrows(IOException.class, () -> handle.writeTo(destination, ResultSide.REMOTE));
		assertEquals("keep original content", Files.readString(destination));
		assertEquals(1, streamsClosed.get());
		try (var files = Files.list(directory)) {
			assertEquals(1, files.count());
		}
	}

	@Test void replacingAFilePreservesItsAccessPermissions() throws Exception {
		Path destination = directory.resolve("private.txt");
		Files.writeString(destination, "old content");
		AclFileAttributeView acl = Files.getFileAttributeView(destination, AclFileAttributeView.class);
		PosixFileAttributeView posix = Files.getFileAttributeView(destination, PosixFileAttributeView.class);
		if (acl != null)
			acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(destination))
					.setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
		if (posix != null)
			Files.setPosixFilePermissions(destination, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		var expectedAcl = acl != null ? acl.getAcl() : null;
		var expectedPermissions = posix != null ? posix.readAttributes().permissions() : null;
		FileSyncHandle handle = new FileSyncHandle("private.txt");
		handle.setRemoteBytes("selected content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		handle.writeTo(destination, ResultSide.REMOTE);
		assertEquals("selected content", Files.readString(destination));
		if (acl != null)
			assertEquals(expectedAcl, Files.getFileAttributeView(destination, AclFileAttributeView.class).getAcl());
		if (posix != null)
			assertEquals(expectedPermissions, Files.getPosixFilePermissions(destination));
	}

	private ObjectLoader streamingObject(boolean fail, AtomicInteger streamsClosed) {
		return new ObjectLoader() {
			@Override public int getType() { return Constants.OBJ_BLOB; }
			@Override public long getSize() { return 8L * 1024 * 1024; }
			@Override public byte[] getCachedBytes() { throw new AssertionError("Must stream large files"); }
			@Override public ObjectStream openStream() {
				return new ObjectStream.Filter(getType(), getSize(), new InputStream() {
					private long remaining = getSize();
					@Override public int read() throws IOException {
						byte[] single = new byte[1];
						return read(single, 0, 1) == -1 ? -1 : single[0];
					}
					@Override public int read(byte[] bytes, int offset, int length) throws IOException {
						if (fail && remaining < getSize())
							throw new IOException("Interrupted object stream");
						if (remaining == 0)
							return -1;
						int count = (int) Math.min(length, remaining);
						Arrays.fill(bytes, offset, offset + count, (byte) 42);
						remaining -= count;
						return count;
					}
					@Override public void close() { streamsClosed.incrementAndGet(); }
				});
			}
		};
	}

	@Test void remoteDeletionRemainsDeletionWhenLocalFileWasModified() throws Exception {
		FileSyncHandle handle = new FileSyncHandle("file.txt");
		handle.setBaseBytes(new byte[] { 0 });
		handle.setLocalBytes(new byte[] { 1 });
		MergeHandle<FileSyncHandle> merge = handle.toPathMergeHandle();
		merge.selectResultSide(ResultSide.REMOTE);
		assertEquals(DiffEntry.ChangeType.DELETE, merge.getSelectedResultChangeType());
		assertNull(handle.getBytes(merge.getResultSide()));
		assertEquals(DiffEntry.ChangeType.MODIFY, handle.getChangeTypeRelativeToLocal());
	}

	@Test void remoteModificationRemainsModificationWhenLocalFileWasDeleted() throws Exception {
		FileSyncHandle handle = new FileSyncHandle("file.txt");
		handle.setBaseBytes(new byte[] { 0 });
		handle.setRemoteBytes(new byte[] { 2 });
		MergeHandle<FileSyncHandle> merge = handle.toPathMergeHandle();
		merge.selectResultSide(ResultSide.REMOTE);
		assertEquals(DiffEntry.ChangeType.MODIFY, merge.getSelectedResultChangeType());
		assertArrayEquals(new byte[] { 2 }, handle.getBytes(merge.getResultSide()));
		assertEquals(DiffEntry.ChangeType.DELETE, handle.getChangeTypeRelativeToLocal());
	}

	@Test void remoteAdditionDoesNotDependOnLocalContent() {
		FileSyncHandle handle = new FileSyncHandle("file.txt");
		handle.setRemoteBytes(new byte[] { 2 });
		assertEquals(DiffEntry.ChangeType.ADD, handle.getChangeTypeRelativeToRemote());
		assertNull(handle.getChangeTypeRelativeToLocal());
	}

	@Test void localAdditionDoesNotBecomeRemoteAddition() {
		FileSyncHandle handle = new FileSyncHandle("file.txt");
		handle.setLocalBytes(new byte[] { 1 });
		assertEquals(DiffEntry.ChangeType.ADD, handle.getChangeTypeRelativeToLocal());
		assertNull(handle.getChangeTypeRelativeToRemote());
	}
}
