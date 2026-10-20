/*
 * MCreator VCS plugin
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

package net.mcreator.workspace;

import net.mcreator.vcs.util.CheckedWorkspaceIO;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TerribleWorkspaceHacks {

	private static final Logger LOG = LogManager.getLogger(TerribleWorkspaceHacks.class);

	public static void loadStoredDataFrom(Workspace workspace, Workspace other) {
		workspace.loadStoredDataFrom(other);
	}

	public static void reloadFromFS(Workspace workspace) throws IOException {
		CheckedWorkspaceIO.reload(workspace);
		LOG.info("Reloaded current workspace from the workspace file");
	}

}
