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

package net.mcreator.vcs.util;

import net.mcreator.ui.component.util.ThreadUtil;
import net.mcreator.ui.dialogs.ProgressDialog;
import net.mcreator.ui.init.L10N;
import org.eclipse.jgit.lib.ProgressMonitor;

import javax.swing.*;
import java.awt.*;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class DialogProgressMonitor implements ProgressMonitor {
	private final String title;
	private final ProgressDialog dialog;
	private final Stack<ProgressDialog.ProgressUnit> units = new Stack<>();
	private int tasksTotal = -1, doneTotal;
	private int tasksCurrent;
	private long doneCurrent;

	public static <T> T runTask(DialogProgressMonitor monitor, String threadName, Callable<T> task) throws Exception {
		FutureTask<T> result = new FutureTask<>(() -> {
			try {
				return task.call();
			} catch (Exception ex) {
				SwingUtilities.invokeLater(monitor::fail);
				throw ex;
			}
		}) {
			@Override protected void done() {
				SwingUtilities.invokeLater(() -> monitor.setVisible(false));
			}
		};
		ThreadUtil.runOnSwingThreadAndWait(() -> {
			(threadName != null ? new Thread(result, threadName) : new Thread(result)).start();
			monitor.setVisible(true);
		});

		try {
			return result.get();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw ex;
		} catch (ExecutionException ex) {
			if (ex.getCause() instanceof Exception cause)
				throw cause;
			if (ex.getCause() instanceof Error cause)
				throw cause;
			throw new IllegalStateException(ex.getCause());
		}
	}

	public DialogProgressMonitor(Window parent, String title) {
		dialog = new ProgressDialog(parent, this.title = title);
	}

	public void setVisible(boolean visible) {
		if (visible)
			dialog.setVisible(true);
		else
			dialog.hideDialog();
	}

	public void fail() {
		if (!units.isEmpty())
			units.pop().markStateError();
	}

	@Override public void start(int totalTasks) {
		tasksTotal = totalTasks;
		dialog.setTitle(
				tasksTotal == -1 ? title : L10N.t("dialog.vcs.progress.title", title, doneTotal = 0, tasksTotal));
	}

	@Override public void beginTask(String title, int totalWork) {
		dialog.addProgressUnit(units.push(new ProgressDialog.ProgressUnit(title)));
		tasksCurrent = totalWork;
		doneCurrent = 0;
	}

	@Override public void update(int completed) {
		doneCurrent += completed;
		if (!units.isEmpty()) {
			ProgressDialog.ProgressUnit unit = units.peek();
			int percent = tasksCurrent > 0 ? (int) Math.clamp(doneCurrent * 100 / tasksCurrent, 0, 100) : 0;
			ThreadUtil.runOnSwingThread(() -> unit.setPercent(percent));
		}
	}

	@Override public void endTask() {
		if (!units.isEmpty())
			units.pop().markStateOk();
		dialog.setTitle(tasksTotal == -1 ? title : L10N.t("dialog.vcs.progress.title", title, ++doneTotal, tasksTotal));
	}

	@Override public boolean isCancelled() {
		return false;
	}

	@Override public void showDuration(boolean b) {
	}

}
