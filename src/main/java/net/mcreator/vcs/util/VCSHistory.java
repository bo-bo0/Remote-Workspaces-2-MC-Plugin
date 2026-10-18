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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import javax.swing.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

public class VCSHistory {

	public static boolean isInCurrentHistory(Git git, ObjectId commit) throws IOException {
		ObjectId head = git.getRepository().resolve(Constants.HEAD);
		if (head == null)
			return false;
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			return walk.isMergedInto(walk.parseCommit(commit), walk.parseCommit(head));
		}
	}

	public static Page readPage(Git git, ObjectId head, int offset, int size) throws IOException, GitAPIException {
		if (offset < 0 || size < 1 || size == Integer.MAX_VALUE)
			throw new IllegalArgumentException();
		String branch = git.getRepository().getBranch();
		ObjectId revision = head != null ? head : git.getRepository().resolve(Constants.HEAD);
		List<RevCommit> commits = new ArrayList<>();
		if (revision != null)
			for (RevCommit commit : git.log().add(revision).setSkip(offset).setMaxCount(size + 1).call()) {
				if (Thread.currentThread().isInterrupted())
					throw new IOException("History loading interrupted");
				commits.add(commit);
			}
		boolean hasMore = commits.size() > size;
		if (hasMore)
			commits.removeLast();
		return new Page(revision, branch, List.copyOf(commits), hasMore);
	}

	public static SwingWorker<Page, Void> loadPage(Git git, ObjectId head, int offset, int size,
			Consumer<Page> loaded, Consumer<Exception> failed) {
		SwingWorker<Page, Void> worker = new SwingWorker<>() {
			@Override protected Page doInBackground() throws Exception {
				return readPage(git, head, offset, size);
			}

			@Override protected void done() {
				if (isCancelled())
					return;
				try {
					loaded.accept(get());
				} catch (CancellationException ignored) {
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					failed.accept(ex);
				} catch (ExecutionException ex) {
					failed.accept(ex.getCause() instanceof Exception cause ? cause : new IllegalStateException(ex.getCause()));
				}
			}
		};
		worker.execute();
		return worker;
	}

	public record Page(ObjectId head, String branch, List<RevCommit> commits, boolean hasMore) {}
}
