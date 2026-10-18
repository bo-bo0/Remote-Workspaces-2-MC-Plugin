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

import net.mcreator.vcs.workspace.VCSInfo;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.merge.MergeMessageFormatter;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import java.io.IOException;
import java.util.Collections;

public class VCSCommits {

	public static RemoteSyncResult fetchAndMerge(Git git, VCSInfo info, CredentialsProvider credentials,
			ProgressMonitor monitor, ICustomSyncHandler handler) throws GitAPIException, IOException {
		// next we check if there are any commits on the remote
		FetchResult fetch = git.fetch().setRemote("origin").setCredentialsProvider(credentials)
				.setProgressMonitor(monitor).call();
		ObjectId remote = remoteHead(git, fetch);
		if (remote == null)
			return null;
		ObjectId head = git.getRepository().resolve(Constants.HEAD);
		String mergeMessage = new MergeMessageFormatter().format(Collections.singletonList(
				new ObjectIdRef.Unpeeled(Ref.Storage.LOOSE, remote.getName(), remote)),
				new ObjectIdRef.Unpeeled(Ref.Storage.LOOSE, head.getName(), head));
		SyncTwoRefsWithMerge.SyncResult result = SyncTwoRefsWithMerge.sync(git, head, remote, handler, () -> {
			// fix in case if merge was not committed yet
			if (git.getRepository().getRepositoryState() == RepositoryState.MERGING_RESOLVED) {
				git.rm().addFilepattern(".").call();
				git.add().addFilepattern(".").call();
				commit(git, info).setAll(true).setMessage(mergeMessage).call();
			}
			// we pull changes before custom merge handler tasks
			mergeRemote(git, remote, info, monitor);
		}, false);
		return new RemoteSyncResult(mergeMessage, result.requiredCustomMergeHandler());
	}

	public record RemoteSyncResult(String mergeMessage, boolean requiredCustomMergeHandler) {}

	public static void checkPushResults(Iterable<PushResult> results) throws IOException {
		boolean hasUpdates = false;
		for (PushResult result : results) {
			for (RemoteRefUpdate update : result.getRemoteUpdates()) {
				hasUpdates = true;
				if (update.getStatus() != RemoteRefUpdate.Status.OK
						&& update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
					throw new IOException(update.getRemoteName() + ": " + update.getStatus()
							+ (update.getMessage() != null ? " - " + update.getMessage() : ""));
			}
		}
		if (!hasUpdates)
			throw new IOException("Git push returned no ref updates");
	}

	public static CommitCommand commit(Git git, VCSInfo info) {
		PersonIdent identity = info.getCommitIdentity();
		return git.commit().setAuthor(identity).setCommitter(identity);
	}

	public static ObjectId remoteHead(Git git, FetchResult fetchResult) throws IOException {
		String remoteBranch = new BranchConfig(git.getRepository().getConfig(),
				git.getRepository().getBranch()).getMerge();
		Ref remoteRef = fetchResult.getAdvertisedRef(remoteBranch != null ? remoteBranch
				: git.getRepository().getFullBranch());
		return remoteRef != null ? remoteRef.getObjectId() : null;
	}

	public static void merge(Git git, MergeCommand command, VCSInfo info) throws GitAPIException, IOException {
		MergeResult result = command.setCommit(false).call();
		if (result.getMergeStatus() == MergeResult.MergeStatus.MERGED_NOT_COMMITTED)
			commit(git, info).setMessage(git.getRepository().readMergeCommitMsg()).call();
		else if (!result.getMergeStatus().isSuccessful()
				&& result.getMergeStatus() != MergeResult.MergeStatus.CONFLICTING)
			throw new IOException("Git merge failed: " + result.getMergeStatus());
	}

	public static void mergeRemote(Git git, ObjectId remote, VCSInfo info, ProgressMonitor monitor)
			throws GitAPIException, IOException {
		MergeCommand.FastForwardMode.Merge mode = git.getRepository().getConfig()
				.getEnum(MergeCommand.FastForwardMode.Merge.values(), "pull", null, "ff");
		merge(git, git.merge().include(remote).setProgressMonitor(monitor)
				.setFastForward(mode != null ? MergeCommand.FastForwardMode.valueOf(mode) : null), info);
	}

}
