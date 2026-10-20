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

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;

import java.io.IOException;
import java.net.URISyntaxException;

public class VCSBranches {

	public static void delete(Git git, Ref ref, CredentialsProvider credentials, ProgressMonitor monitor)
			throws GitAPIException, IOException, URISyntaxException {
		if (!ref.getName().startsWith(Constants.R_REMOTES)) {
			deleteLocal(git, ref.getName());
			return;
		}
		RemoteBranch target = remoteBranch(git, ref.getName());
		if (ref.isSymbolic())
			throw new IOException("Cannot delete a symbolic remote branch: " + ref.getName());
		VCSCommits.checkPushResults(git.push().setRemote(target.remote())
				.setRefSpecs(new RefSpec(":" + target.branch())).setCredentialsProvider(credentials)
				.setProgressMonitor(monitor).call());
		git.fetch().setRemote(target.remote()).setRemoveDeletedRefs(true).setCredentialsProvider(credentials)
				.setProgressMonitor(monitor).call();
	}

	public static RemoteBranch remoteBranch(Git git, String name) throws IOException, URISyntaxException {
		RemoteBranch result = null;
		for (RemoteConfig remote : RemoteConfig.getAllRemoteConfigs(git.getRepository().getConfig())) {
			for (RefSpec spec : remote.getFetchRefSpecs()) {
				if (spec.matchDestination(name)) {
					String branch = spec.expandFromDestination(name).getSource();
					RemoteBranch candidate = new RemoteBranch(remote.getName(), branch);
					if (branch == null || !branch.startsWith(Constants.R_HEADS)
							|| result != null && !result.equals(candidate))
						throw new IOException("Ambiguous remote branch: " + name);
					result = candidate;
				}
			}
		}
		if (result == null)
			throw new IOException("Unknown remote branch: " + name);
		return result;
	}

	public record RemoteBranch(String remote, String branch) {}

	public static void deleteLocal(Git git, String name) throws GitAPIException {
		git.branchDelete().setBranchNames(name).setForce(true).call();
	}
}
