/*
 * MCreator VCS plugin
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

import com.google.gson.JsonParser;
import net.mcreator.ui.MCreator;
import net.mcreator.ui.init.L10N;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.WorkspaceFileManager;
import net.mcreator.vcs.util.diff.ResultSide;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;

public class CheckedWorkspaceIO {

	public static boolean save(MCreator mcreator) {
		try {
			save(mcreator.getWorkspace());
			return true;
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(mcreator, L10N.t("dialog.vcs.error.sync_failed.message", ex.getMessage()),
					L10N.t("dialog.vcs.error.sync_failed.title"), JOptionPane.ERROR_MESSAGE);
			return false;
		}
	}

	public static void save(Workspace workspace) throws IOException {
		Path file = workspace.getFileManager().getWorkspaceFile().toPath();
		byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
		var expected = WorkspaceFileManager.gson.toJsonTree(workspace, Workspace.class);
		try {
			workspace.getFileManager().saveWorkspaceDirectlyAndWait();
			if (!expected.equals(JsonParser.parseString(Files.readString(file))))
				throw new IOException("Workspace save did not preserve the expected data: " + file);
		} catch (IOException | RuntimeException ex) {
			workspace.markDirty();
			IOException failure = ex instanceof IOException io ? io : new IOException("Workspace save failed: " + file, ex);
			try {
				if (before == null) {
					Files.deleteIfExists(file);
				} else if (!Files.isRegularFile(file) || !Arrays.equals(before, Files.readAllBytes(file))) {
					FileSyncHandle recovery = new FileSyncHandle(file.getFileName().toString());
					recovery.setLocalBytes(before);
					recovery.writeTo(file, ResultSide.LOCAL);
				}
			} catch (IOException | RuntimeException restoreFailure) {
				failure.addSuppressed(restoreFailure);
			}
			throw failure;
		}
	}

	static void delete(Workspace workspace, Path file) throws IOException {
		Path root = workspace.getWorkspaceFolder().getCanonicalFile().toPath();
		Path target = file.toFile().getCanonicalFile().toPath();
		if (target.equals(root) || !target.startsWith(root))
			throw new IOException("File is outside the workspace: " + file);
		if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Cannot delete a directory while resolving a file: " + file);
		Files.deleteIfExists(file);
	}
}
