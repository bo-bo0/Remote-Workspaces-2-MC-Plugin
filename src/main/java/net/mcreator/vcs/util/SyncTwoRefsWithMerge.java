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

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.RecursiveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SyncTwoRefsWithMerge {

	public static SyncResult sync(Git git, @Nonnull ObjectId local, @Nonnull ObjectId remote,
			ICustomSyncHandler customSyncHandler, @Nullable PreCustomMergeAction preCustomMergeAction, boolean dryRun)
			throws GitAPIException, IOException {

		try (RevWalk walk = new RevWalk(git.getRepository())) {
			if (walk.isMergedInto(walk.parseCommit(remote), walk.parseCommit(local)))
				return new SyncResult(false, false);
		}

		RecursiveMerger merger = (RecursiveMerger) MergeStrategy.RECURSIVE.newMerger(git.getRepository(),
				true); // in core -> dry run
		boolean requiredCustomMergeHandler = !merger.merge(local,
				remote); // first we check if there are merge conflicts

		List<FileSyncHandle> fileSyncHandles = new ArrayList<>();
		Map<String, FileSyncHandle> handlesByBasePath = new HashMap<>();

		// get the merge base commit from the merge based used by git merger
		RevCommit mergeBase = VCSUtils.commitFromObjectId(git, merger.getBaseCommitId());

		AbstractTreeIterator remoteTreeParser = VCSUtils.prepareTreeParser(git, remote);
		AbstractTreeIterator localTreeParser = VCSUtils.prepareTreeParser(git, local);
		AbstractTreeIterator baseTreeParser = VCSUtils.prepareTreeParser(git, mergeBase);

		List<DiffEntry> baseToLocalDiff = git.diff().setOldTree(baseTreeParser).setNewTree(localTreeParser).call();
		List<DiffEntry> baseToRemoteDiff = git.diff().setOldTree(VCSUtils.prepareTreeParser(git, mergeBase))
				.setNewTree(remoteTreeParser).call();

		Set<String> unmergedPaths = new HashSet<>(merger.getUnmergedPaths()); // paths that got conflicted by git merge

		for (DiffEntry entry : baseToLocalDiff) {
			String basePath = entry.getOldPath();
			String localPath = entry.getNewPath();

			FileSyncHandle.ObjectSource localObject = objectSource(git, entry.getNewId().toObjectId());
			FileSyncHandle.ObjectSource baseObject = objectSource(git, entry.getOldId().toObjectId());

			if (localObject == null)
				localPath = basePath;
			if (baseObject == null)
				basePath = localPath;

			FileSyncHandle fileSyncHandle = new FileSyncHandle(basePath);
			fileSyncHandle.setLocalPath(localPath);
			fileSyncHandle.setUnmerged(unmergedPaths.contains(basePath) || unmergedPaths.contains(localPath));
			fileSyncHandle.setBaseObject(baseObject);
			fileSyncHandle.setRemotePath(basePath);
			fileSyncHandle.setRemoteObject(baseObject);
			fileSyncHandle.setLocalObject(localObject);
			fileSyncHandles.add(fileSyncHandle);
			handlesByBasePath.putIfAbsent(basePath, fileSyncHandle);

		}

		for (DiffEntry entry : baseToRemoteDiff) {
			String basePath = entry.getOldPath();
			String remotePath = entry.getNewPath();

			FileSyncHandle.ObjectSource remoteObject = objectSource(git, entry.getNewId().toObjectId());
			FileSyncHandle.ObjectSource baseObject = objectSource(git, entry.getOldId().toObjectId());

			if (remoteObject == null)
				remotePath = basePath;
			if (baseObject == null)
				basePath = remotePath;

			// try to find existing handle
			FileSyncHandle fileSyncHandle = handlesByBasePath.get(basePath);
			// if we fail to find a handle, make a new one
			if (fileSyncHandle == null) {
				fileSyncHandle = new FileSyncHandle(basePath);
				fileSyncHandle.setBaseObject(baseObject);
				fileSyncHandle.setLocalPath(basePath);
				fileSyncHandle.setLocalObject(baseObject);
				fileSyncHandles.add(fileSyncHandle);
				handlesByBasePath.put(basePath, fileSyncHandle);
			}
			if (!fileSyncHandle.isUnmerged()) { // if not marked as unmerged yet, we might need to do it at this point
				fileSyncHandle.setUnmerged(unmergedPaths.contains(basePath) || unmergedPaths.contains(remotePath));
			}

			fileSyncHandle.setRemoteObject(remoteObject);
			fileSyncHandle.setRemotePath(remotePath);
		}

		if (preCustomMergeAction != null)
			preCustomMergeAction.call();

		// after pull, we handle merge conflicts we predicted before
		boolean requiredUserInteraction = customSyncHandler.handleSync(git, requiredCustomMergeHandler, fileSyncHandles,
				dryRun);

		return new SyncResult(requiredCustomMergeHandler, requiredUserInteraction);
	}

	private static FileSyncHandle.ObjectSource objectSource(Git git, ObjectId id) throws IOException {
		if (ObjectId.zeroId().equals(id) || !git.getRepository().getObjectDatabase().has(id))
			return null;
		return () -> git.getRepository().getObjectDatabase().open(id);
	}

	public interface PreCustomMergeAction {
		void call() throws GitAPIException, IOException;
	}

	public record SyncResult(boolean requiredCustomMergeHandler, boolean requiredUserAction) {}

}
