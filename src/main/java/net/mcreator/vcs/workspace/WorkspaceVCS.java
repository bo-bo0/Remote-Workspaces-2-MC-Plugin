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

package net.mcreator.vcs.workspace;

import net.mcreator.io.FileIO;
import net.mcreator.plugin.PluginLoader;
import net.mcreator.ui.init.L10N;
import net.mcreator.vcs.util.DialogProgressMonitor;
import net.mcreator.workspace.Workspace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkspaceVCS {

	private static final Logger LOG = LogManager.getLogger("Workspace VCS");
	private static final Map<File, WorkspaceVCS> workspaces = new ConcurrentHashMap<>();
	private final Workspace workspace;

	private VCSInfo info;

	private Git git;

	private WorkspaceVCS(Workspace workspace, VCSInfo info) {
		this.workspace = workspace;
		this.info = info;

		try {
			this.git = Git.init().setDirectory(workspace.getWorkspaceFolder()).call();
			WorkspaceVCS previous = workspaces.put(workspace.getWorkspaceFolder(), this);
			if (previous != null)
				previous.git.close();
		} catch (GitAPIException e) {
			LOG.error("Failed to load repository", e);
		}
	}

	public void setInfo(VCSInfo info) {
		this.info = info;
	}

	public VCSInfo getInfo() {
		return info;
	}

	public Git getGit() {
		return git;
	}

	public CredentialsProvider getCredentialsProvider(File workspaceFolder, Window parent) {
		return info.getCredentialsProvider(workspaceFolder, parent);
	}

	public static boolean initNewVCSWorkspace(Workspace workspace, VCSInfo vcsInfo, Window parent) {
		Git git = null;
		try {
			CredentialsProvider credentials = vcsInfo.getCredentialsProvider(workspace.getWorkspaceFolder(), parent);
			DialogProgressMonitor monitor = new DialogProgressMonitor(parent,
					L10N.t("dialog.vcs.setup_remote_workspace_details"));
			// try to fetch, reject this workspace if there is existing content in it
			if (!DialogProgressMonitor.runTask(monitor, "WorkspaceVCS-ValidateRemote",
					() -> Git.lsRemoteRepository().setRemote(vcsInfo.getRemote())
							.setCredentialsProvider(credentials).call()).isEmpty()) {
				JOptionPane.showMessageDialog(parent, L10N.t("dialog.vcs.setup.workspace_folder_not_empty.message"),
						L10N.t("dialog.vcs.setup.workspace_folder_not_empty.title"), JOptionPane.ERROR_MESSAGE);
				return false;
			}

			git = Git.init().setDirectory(workspace.getWorkspaceFolder()).call();

			// add remote first
			RemoteAddCommand remoteAddCommand = git.remoteAdd();
			remoteAddCommand.setName("origin");
			remoteAddCommand.setUri(new URIish(vcsInfo.getRemote()));
			remoteAddCommand.call();

			// setup branch "master" section
			StoredConfig config = git.getRepository().getConfig();
			config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, "master", ConfigConstants.CONFIG_KEY_REMOTE,
					"origin");
			config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, "master", ConfigConstants.CONFIG_KEY_MERGE,
					Constants.R_HEADS + "master");
			config.save();
			git.close();

		} catch (Exception e) {
			if (git != null)
				git.close();
			LOG.error("Failed to init GIT repository", e);
			return false;
		}

		// create gitignore
		String gitignore = FileIO.readResourceToString(PluginLoader.INSTANCE,
				workspace.getGenerator().getGeneratorName() + "/workspace.gitignore");
		FileIO.writeStringToFile(gitignore, new File(workspace.getWorkspaceFolder(), ".gitignore"));

		VCSInfo.saveToFile(vcsInfo, new File(workspace.getFolderManager().getWorkspaceCacheDir(), "vcsInfo"));

		new WorkspaceVCS(workspace, vcsInfo);
		return workspaces.containsKey(workspace.getWorkspaceFolder());
	}

	public static boolean loadVCSWorkspace(Workspace workspace) {
		if (isVCSInitialized(workspace)) {
			VCSInfo vcsInfo = VCSInfo.loadFromFile(
					new File(workspace.getFolderManager().getWorkspaceCacheDir(), "vcsInfo"));
			if (vcsInfo != null) {
				new WorkspaceVCS(workspace, vcsInfo);
				return true;
			}
		}
		return false;
	}

	public static WorkspaceVCS getVCSWorkspace(Workspace workspace) {
		return workspaces.get(workspace.getWorkspaceFolder());
	}

	public static void removeVCSWorkspace(Workspace workspace) {
		new File(workspace.getFolderManager().getWorkspaceCacheDir(), "vcsInfo").delete();
		closeVCS(workspaces.remove(workspace.getWorkspaceFolder()).git);
	}

	public static void releaseVCSWorkspace(Workspace workspace) {
		WorkspaceVCS current = workspaces.get(workspace.getWorkspaceFolder());
		if (current != null && current.workspace == workspace
				&& workspaces.remove(workspace.getWorkspaceFolder(), current))
			current.git.close();
	}

	private static boolean isVCSInitialized(Workspace workspace) {
		try {
			if (new File(workspace.getFolderManager().getWorkspaceCacheDir(), "vcsInfo").isFile()) {
				try (Git git = Git.init().setDirectory(workspace.getWorkspaceFolder()).call()) {
					return !git.getRepository().getRemoteNames().isEmpty();
				}
			}
		} catch (GitAPIException e) {
			LOG.warn("Failed to check for Git repo", e);
		}
		return false;
	}

	private static void closeVCS(Git git) {
		git.getRepository().close();
		git.close();
		FileIO.deleteDir(git.getRepository().getDirectory());
	}

}
