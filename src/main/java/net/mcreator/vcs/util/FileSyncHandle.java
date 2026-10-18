/*
 * MCreator VCS plugin
 * Copyright (C) 2020 Pylo and contributors
 * Copyright (C) 2023, Defeatomizer
 * Copyright (C) 2026, bo bo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.vcs.util;

import net.mcreator.vcs.util.diff.MergeHandle;
import net.mcreator.vcs.util.diff.ResultSide;
import net.mcreator.workspace.Workspace;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectLoader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;

public class FileSyncHandle {

	private byte[] remoteBytes, localBytes, baseBytes;
	private ObjectSource remoteObject, localObject, baseObject;
	private String remotePath;
	private String localPath;
	private final String basePath;
	private boolean unmerged;

	@FunctionalInterface interface ObjectSource {
		ObjectLoader open() throws IOException;
	}

	FileSyncHandle(String basePath) {
		this.basePath = basePath;
	}

	void setRemoteBytes(byte[] remoteBytes) {
		this.remoteBytes = remoteBytes;
		this.remoteObject = null;
	}

	void setLocalBytes(byte[] localBytes) {
		this.localBytes = localBytes;
		this.localObject = null;
	}

	void setBaseBytes(byte[] baseBytes) {
		this.baseBytes = baseBytes;
		this.baseObject = null;
	}

	void setRemoteObject(ObjectSource remoteObject) {
		this.remoteObject = remoteObject;
		this.remoteBytes = null;
	}

	void setLocalObject(ObjectSource localObject) {
		this.localObject = localObject;
		this.localBytes = null;
	}

	void setBaseObject(ObjectSource baseObject) {
		this.baseObject = baseObject;
		this.baseBytes = null;
	}

	void setUnmerged(boolean unmerged) {
		this.unmerged = unmerged;
	}

	void setRemotePath(String remotePath) {
		this.remotePath = remotePath;
	}

	void setLocalPath(String localPath) {
		this.localPath = localPath;
	}

	public byte[] getRemoteBytes() throws IOException {
		return remoteObject != null ? remoteObject.open().getBytes() : remoteBytes;
	}

	public byte[] getLocalBytes() throws IOException {
		return localObject != null ? localObject.open().getBytes() : localBytes;
	}

	public byte[] getBaseBytes() throws IOException {
		return baseObject != null ? baseObject.open().getBytes() : baseBytes;
	}

	public String getBasePath() {
		return basePath;
	}

	public String getRemotePath() {
		return remotePath;
	}

	public String getLocalPath() {
		return localPath;
	}

	public String getPath(ResultSide resultSide) {
		return switch (resultSide) {
			case LOCAL -> getLocalPath();
			case REMOTE -> getRemotePath();
			default -> getBasePath();
		};
	}

	public byte[] getBytes(ResultSide resultSide) throws IOException {
		return switch (resultSide) {
			case LOCAL -> getLocalBytes();
			case REMOTE -> getRemoteBytes();
			default -> getBaseBytes();
		};
	}

	void writeTo(Path destination, ResultSide resultSide) throws IOException {
		ObjectSource object = switch (resultSide) {
			case LOCAL -> localObject;
			case REMOTE -> remoteObject;
			default -> baseObject;
		};
		Path parent = destination.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".vcs-merge-", ".tmp");
		try {
			PosixFileAttributeView attributes = Files.getFileAttributeView(destination, PosixFileAttributeView.class,
					LinkOption.NOFOLLOW_LINKS);
			if (attributes != null && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
				Files.setPosixFilePermissions(temporary, attributes.readAttributes().permissions());
			AclFileAttributeView acl = Files.getFileAttributeView(destination, AclFileAttributeView.class,
					LinkOption.NOFOLLOW_LINKS);
			if (acl != null && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
				Files.getFileAttributeView(temporary, AclFileAttributeView.class).setAcl(acl.getAcl());
			try (InputStream input = object != null ? object.open().openStream() : new ByteArrayInputStream(getBytes(resultSide));
					OutputStream output = Files.newOutputStream(temporary)) {
				input.transferTo(output);
			}
			try {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ex) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private boolean hasBase() {
		return baseObject != null || baseBytes != null;
	}

	private boolean hasLocal() {
		return localObject != null || localBytes != null;
	}

	private boolean hasRemote() {
		return remoteObject != null || remoteBytes != null;
	}

	public DiffEntry.ChangeType getChangeTypeRelativeTo(ResultSide resultSide) {
		return switch (resultSide) {
			case LOCAL -> getChangeTypeRelativeToLocal();
			case REMOTE -> getChangeTypeRelativeToRemote();
			default -> null;
		};
	}

	public DiffEntry.ChangeType getChangeTypeRelativeToLocal() {
		if (!hasBase())
			if (hasLocal())
				return DiffEntry.ChangeType.ADD;

		if (hasBase())
			if (!hasLocal())
				return DiffEntry.ChangeType.DELETE;

		if (hasBase())
			return DiffEntry.ChangeType.MODIFY;

		return null;
	}

	public DiffEntry.ChangeType getChangeTypeRelativeToRemote() {
		if (!hasBase())
			if (hasRemote())
				return DiffEntry.ChangeType.ADD;

		if (hasBase())
			if (!hasRemote())
				return DiffEntry.ChangeType.DELETE;

		if (hasBase())
			return DiffEntry.ChangeType.MODIFY;

		return null;
	}

	public boolean isUnmerged() {
		return unmerged;
	}

	public MergeHandle<FileSyncHandle> toPathMergeHandle() {
		return new MergeHandle<>(this, this, getChangeTypeRelativeToLocal(), getChangeTypeRelativeToRemote());
	}

	public File toFileInWorkspace(Workspace workspace, ResultSide resultSide) {
		return new File(workspace.getWorkspaceFolder(), getPath(resultSide));
	}

	@Override public boolean equals(Object o) {
		return o instanceof FileSyncHandle other && other.basePath.equals(basePath);
	}

	@Override public int hashCode() {
		return basePath.hashCode();
	}

	@Override public String toString() {
		return basePath + (unmerged ? " (unmerged)" : "") + " - " + (hasRemote() ? "R" : "") + (
				hasLocal() ? "L" : "") + (hasBase() ? "B" : "");
	}
}
