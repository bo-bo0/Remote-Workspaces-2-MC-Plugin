package net.mcreator.vcs.util;

import net.mcreator.vcs.util.diff.MergeHandle;
import net.mcreator.vcs.util.diff.ResultSide;
import org.eclipse.jgit.diff.DiffEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileSyncHandleTest {

	@Test void remoteDeletionRemainsDeletionWhenLocalFileWasModified() {
		FileSyncHandle handle = new FileSyncHandle("file.txt");
		handle.setBaseBytes(new byte[] { 0 });
		handle.setLocalBytes(new byte[] { 1 });
		MergeHandle<FileSyncHandle> merge = handle.toPathMergeHandle();
		merge.selectResultSide(ResultSide.REMOTE);
		assertEquals(DiffEntry.ChangeType.DELETE, merge.getSelectedResultChangeType());
		assertNull(handle.getBytes(merge.getResultSide()));
		assertEquals(DiffEntry.ChangeType.MODIFY, handle.getChangeTypeRelativeToLocal());
	}

	@Test void remoteModificationRemainsModificationWhenLocalFileWasDeleted() {
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
