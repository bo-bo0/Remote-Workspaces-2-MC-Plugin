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

package net.mcreator.vcs.ui.workspace;

import net.mcreator.ui.component.TransparentToolBar;
import net.mcreator.ui.component.util.ComponentUtils;
import net.mcreator.ui.component.util.ThreadUtil;
import net.mcreator.ui.dialogs.ProgressDialog;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.init.UIRES;
import net.mcreator.ui.laf.themes.Theme;
import net.mcreator.ui.workspace.AbstractMainWorkspacePanel;
import net.mcreator.ui.workspace.AbstractWorkspacePanel;
import net.mcreator.util.FilenameUtilsPatched;
import net.mcreator.vcs.ui.actions.VCSActionRegistry;
import net.mcreator.vcs.ui.actions.impl.SetupVCSAction;
import net.mcreator.vcs.ui.component.BranchesPopup;
import net.mcreator.vcs.util.DialogProgressMonitor;
import net.mcreator.vcs.util.VCSCommits;
import net.mcreator.vcs.util.VCSHistory;
import net.mcreator.vcs.workspace.WorkspaceVCS;
import net.mcreator.workspace.TerribleWorkspaceHacks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;

import javax.annotation.Nonnull;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class WorkspacePanelVCS extends AbstractWorkspacePanel {

	private static final Logger LOG = LogManager.getLogger("VCS Panel");
	private static final int HISTORY_PAGE_SIZE = 100;

	private final List<RevCommit> cachedCommits = new ArrayList<>();
	private final JTable commits;
	private final TableRowSorter<TableModel> sorter;
	private SwingWorker<VCSHistory.Page, Void> historyWorker;
	private ObjectId historyHead;
	private String historyBranch;
	private int historyRequest;
	private boolean historyLoading;
	private final JButton loadMore = L10N.button("workspace.vcs.load_more_commits");

	private final JButton switchBranch = new JButton(UIRES.get("16px.vcs"));

	public WorkspacePanelVCS(AbstractMainWorkspacePanel workspacePanel) {
		super(workspacePanel);
		setLayout(new BorderLayout(0, 5));

		TransparentToolBar bar = new TransparentToolBar();
		bar.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 0));

		JButton uncommitted = L10N.button("workspace.vcs.show_local_changes");
		uncommitted.setIcon(UIRES.get("16px.info"));
		uncommitted.setContentAreaFilled(false);
		uncommitted.setOpaque(false);
		ComponentUtils.deriveFont(uncommitted, 12);
		uncommitted.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
		bar.add(uncommitted);

		uncommitted.addActionListener(
				e -> VCSActionRegistry.get(workspacePanel.getMCreator()).showUnsyncedChanges.doAction());

		JButton checkout = L10N.button("workspace.vcs.jump_to_selected_commit");
		checkout.setIcon(UIRES.get("16px.rwd"));
		checkout.setContentAreaFilled(false);
		checkout.setOpaque(false);
		ComponentUtils.deriveFont(checkout, 12);
		checkout.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
		bar.add(checkout);

		checkout.addActionListener(e -> checkoutToSelectedCommit());

		switchBranch.setContentAreaFilled(false);
		switchBranch.setOpaque(false);
		ComponentUtils.deriveFont(switchBranch, 12);
		switchBranch.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
		bar.add(switchBranch);

		JButton fetchBranches = L10N.button("dialog.vcs.branches_popup.fetch_branches");
		fetchBranches.setIcon(UIRES.get("16px.ext"));
		fetchBranches.setContentAreaFilled(false);
		fetchBranches.setOpaque(false);
		ComponentUtils.deriveFont(fetchBranches, 12);
		fetchBranches.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
		bar.add(fetchBranches);

		JButton deleteBranch = L10N.button("dialog.vcs.branches_popup.delete_branch");
		deleteBranch.setIcon(UIRES.get("16px.delete"));
		deleteBranch.setContentAreaFilled(false);
		deleteBranch.setOpaque(false);
		ComponentUtils.deriveFont(deleteBranch, 12);
		deleteBranch.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
		bar.add(deleteBranch);

		WorkspaceVCS workspaceVCS = WorkspaceVCS.getVCSWorkspace(workspacePanel.getMCreator().getWorkspace());
		switchBranch.addActionListener(
				e -> new BranchesPopup(workspaceVCS, workspacePanel.getMCreator(), null).show(switchBranch, 4, 20));
		fetchBranches.addActionListener(e -> {
			try {
				DialogProgressMonitor monitor = new DialogProgressMonitor(workspacePanel.getMCreator(),
						L10N.t("dialog.vcs.branches_popup.fetch_branches"));
				DialogProgressMonitor.runTask(monitor, "WorkspacePanelVCS-FetchBranches",
						() -> workspaceVCS.getGit().fetch().setRemote("origin").setRemoveDeletedRefs(true)
								.setCredentialsProvider(workspaceVCS.getCredentialsProvider(
										workspacePanel.getMCreator().getWorkspaceFolder(),
										workspacePanel.getMCreator())).setProgressMonitor(monitor).call());
			} catch (Exception ex) {
				LOG.error("Failed to fetch branches", ex);
			}
		});
		deleteBranch.addActionListener(e -> new BranchesPopup(workspaceVCS, workspacePanel.getMCreator(), ref -> {
			Git git = workspaceVCS.getGit();
			if (JOptionPane.YES_OPTION == JOptionPane.showConfirmDialog(workspacePanel.getMCreator(),
					L10N.t("dialog.vcs.branches_popup.delete_branch.message", ref.getName()),
					L10N.t("dialog.vcs.branches_popup.delete_branch.title"), JOptionPane.YES_NO_OPTION)) {
				try {
					CredentialsProvider credentialsProvider = workspaceVCS.getCredentialsProvider(
							workspacePanel.getMCreator().getWorkspaceFolder(), workspacePanel.getMCreator());
					DialogProgressMonitor monitor = new DialogProgressMonitor(workspacePanel.getMCreator(),
							L10N.t("dialog.vcs.branches_popup.delete_branch"));
					DialogProgressMonitor.runTask(monitor, "BranchesPopup-DeleteBranch", () -> {
						git.reset().setMode(ResetCommand.ResetType.HARD).call();
						git.branchDelete().setBranchNames(ref.getName()).setForce(true).call();
						if (ref.getName().startsWith(Constants.R_REMOTES)) {
							String dest = Constants.R_HEADS + FilenameUtilsPatched.getName(ref.getName());
							git.push().setRemote("origin").setRefSpecs(new RefSpec(":" + dest).setSource(null))
									.setCredentialsProvider(credentialsProvider).setProgressMonitor(monitor).call();
						}
						return git.fetch().setRemote("origin").setRemoveDeletedRefs(true)
								.setCredentialsProvider(credentialsProvider).setProgressMonitor(monitor).call();
					});
					workspacePanel.reloadWorkspaceTab();
				} catch (Exception er) {
					LOG.error("Failed to delete branch", er);
				}
			}
		}).show(deleteBranch, 4, 20));

		add("North", bar);

		commits = new JTable(new DefaultTableModel(
				new Object[] { L10N.t("workspace.vcs.commit_list.id"), L10N.t("workspace.vcs.commit_list.message"),
						L10N.t("workspace.vcs.commit_list.author"), L10N.t("workspace.vcs.commit_list.date") }, 0) {
			@Override public boolean isCellEditable(int row, int column) {
				return false;
			}
		}) {
			@Override public String getToolTipText(@Nonnull MouseEvent event) {
				int row = rowAtPoint(event.getPoint());
				if (row < 0)
					return null;
				RevCommit commit = cachedCommits.get(convertRowIndexToModel(row));
				return switch (columnAtPoint(event.getPoint())) {
					case 0 -> commit.getName();
					case 1 -> "<html>" + commit.getFullMessage()
							.replaceAll("\\R", "<br>");
					default -> null;
				};
			}
		};

		sorter = new TableRowSorter<>(commits.getModel());
		commits.setRowSorter(sorter);
		commits.setBackground(Theme.current().getBackgroundColor());
		commits.setSelectionBackground(Theme.current().getAltBackgroundColor());
		commits.setForeground(Theme.current().getForegroundColor());
		commits.setSelectionForeground(Theme.current().getForegroundColor());
		commits.setBorder(BorderFactory.createEmptyBorder());
		commits.setGridColor(Theme.current().getAltBackgroundColor());
		commits.setRowHeight(38);
		ComponentUtils.deriveFont(commits, 13);

		commits.getColumnModel().getColumn(0).setMinWidth(58);
		commits.getColumnModel().getColumn(0).setMaxWidth(58);
		commits.getColumnModel().getColumn(0).setPreferredWidth(58);

		commits.getColumnModel().getColumn(1).setPreferredWidth(650);

		JTableHeader header = commits.getTableHeader();
		header.setBackground(Theme.current().getInterfaceAccentColor());
		header.setForeground(Theme.current().getBackgroundColor());

		JScrollPane sp = new JScrollPane(commits);
		sp.setBackground(Theme.current().getBackgroundColor());
		sp.getViewport().setOpaque(false);
		sp.getVerticalScrollBar().setUnitIncrement(11);
		sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));

		sp.setColumnHeaderView(null);

		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
		holder.add(sp);

		add("Center", holder);
		loadMore.setVisible(false);
		loadMore.addActionListener(e -> {
			WorkspaceVCS current = WorkspaceVCS.getVCSWorkspace(workspacePanel.getMCreator().getWorkspace());
			if (current != null && !historyLoading)
				loadHistory(current, true);
		});
		add("South", loadMore);

		commits.addMouseListener(new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent mouseEvent) {
				if (mouseEvent.getClickCount() == 2) {
					checkoutToSelectedCommit();
				}
			}
		});
	}

	private void checkoutToSelectedCommit() {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(this::checkoutToSelectedCommit);
			return;
		}
		int row = commits.getSelectedRow();
		if (row == -1 || historyLoading)
			return;

		WorkspaceVCS workspaceVCS = WorkspaceVCS.getVCSWorkspace(workspacePanel.getMCreator().getWorkspace());
		if (workspaceVCS == null)
			return;
		RevCommit commit = cachedCommits.get(commits.convertRowIndexToModel(row));
		int option = JOptionPane.showOptionDialog(workspacePanel.getMCreator(),
				L10N.t("workspace.vcs.jump_commit_confirmation", commit.getShortMessage()),
				L10N.t("workspace.vcs.jump_commit_confirmation.title"), JOptionPane.DEFAULT_OPTION,
				JOptionPane.QUESTION_MESSAGE, null,
				new String[] { L10N.t("workspace.vcs.jump_to", commit.abbreviate(7).name()),
						UIManager.getString("OptionPane.cancelButtonText") }, null);
		if (option != 0)
			return;

		ProgressDialog pd = new ProgressDialog(workspacePanel.getMCreator(),
				L10N.t("workspace.vcs.jump_to_selected_commit"));
		new Thread(() -> {
			ProgressDialog.ProgressUnit pu = null;
			try {
				Git git = workspaceVCS.getGit();
				if (!VCSHistory.isInCurrentHistory(git, commit))
					throw new IOException("Selected commit is no longer in the current branch history");
				// track all so they can be stashed properly
				pd.addProgressUnit(pu = new ProgressDialog.ProgressUnit(
						L10N.t("workspace.vcs.jump_to_selected_commit.stashing")));
				git.rm().addFilepattern(".").call();
				git.add().addFilepattern(".").call();

				// remove local changes attempt 1
				git.stashCreate().setPerson(workspaceVCS.getInfo().getCommitIdentity()).call();
				git.stashDrop().call();
				ThreadUtil.runOnSwingThreadAndWait(pu::markStateOk);

				ObjectId currentBranchHead = git.getRepository().resolve(Constants.HEAD);
				String oldBranch = git.getRepository().getFullBranch();

				pd.addProgressUnit(pu = new ProgressDialog.ProgressUnit(
						L10N.t("workspace.vcs.jump_to_selected_commit.jumping")));
				git.checkout().setName(commit.getName()).setStartPoint(commit.getName()).call();
				setProgress(pu, 17);
				git.checkout().setName("tmpHistoryBranch" + commit.getName()).setCreateBranch(true).call();
				setProgress(pu, 33);
				String branchName = git.getRepository().getFullBranch();
				VCSCommits.merge(git, git.merge().setStrategy(MergeStrategy.OURS).include(currentBranchHead)
						.setFastForward(MergeCommand.FastForwardMode.NO_FF)
						.setMessage("Jump back to commit " + commit.getName()), workspaceVCS.getInfo());
				setProgress(pu, 50);
				git.checkout().setName(oldBranch).call();
				setProgress(pu, 67);
				VCSCommits.merge(git, git.merge().include(git.getRepository().resolve(branchName)),
						workspaceVCS.getInfo());
				setProgress(pu, 83);
				git.branchDelete().setBranchNames(branchName).call();
				ThreadUtil.runOnSwingThreadAndWait(pu::markStateOk);

				// we might need to make another commit to commit the merge changes
				try {
					pd.addProgressUnit(pu = new ProgressDialog.ProgressUnit(
							L10N.t("workspace.vcs.jump_to_selected_commit.cleaning")));
					git.rm().addFilepattern(".").call();
					git.add().addFilepattern(".").call();
					VCSCommits.commit(git, workspaceVCS.getInfo()).setAll(true).setAllowEmpty(false)
							.setMessage("Jump cleanup commit").call();
					ThreadUtil.runOnSwingThreadAndWait(pu::markStateOk);
				} catch (Exception ignored) {
				}

				TerribleWorkspaceHacks.reloadFromFS(workspacePanel.getMCreator().getWorkspace());
				ThreadUtil.runOnSwingThreadAndWait(() -> {
					workspacePanel.reloadWorkspaceTab();
					workspacePanel.getMCreator().getActionRegistry().buildWorkspace.doAction();
				});
			} catch (Exception ex) {
				LOG.error("Checkout failed!", ex);
				if (pu != null)
					ThreadUtil.runOnSwingThreadAndWait(pu::markStateError);
			} finally {
				pd.hideDialog();
			}
		}, "JumpToCommit").start();
		pd.setVisible(true);
	}

	private static void setProgress(ProgressDialog.ProgressUnit unit, int percent) {
		ThreadUtil.runOnSwingThreadAndWait(() -> unit.setPercent(percent));
	}

	@Override public boolean canSwitchToSection() {
		return SetupVCSAction.setupVCSForWorkspaceIfNotYet(workspacePanel.getMCreator());
	}

	@Override public void reloadElements() {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(this::reloadElements);
			return;
		}
		WorkspaceVCS current = WorkspaceVCS.getVCSWorkspace(workspacePanel.getMCreator().getWorkspace());
		if (historyWorker != null)
			historyWorker.cancel(true);
		historyRequest++;
		if (current != null) {
			loadHistory(current, false);
		} else {
			historyLoading = false;
			historyHead = null;
			((DefaultTableModel) commits.getModel()).setRowCount(0);
			cachedCommits.clear();
			loadMore.setVisible(false);
		}
	}

	private void loadHistory(WorkspaceVCS current, boolean append) {
		int selectedRow = commits.getSelectedRow();
		ObjectId selected = selectedRow >= 0 ? cachedCommits.get(commits.convertRowIndexToModel(selectedRow)).getId() : null;
		int offset = append ? cachedCommits.size() : 0;
		if (!append) {
			((DefaultTableModel) commits.getModel()).setRowCount(0);
			cachedCommits.clear();
			historyHead = null;
			loadMore.setVisible(false);
		}
		int request = ++historyRequest;
		historyLoading = true;
		loadMore.setEnabled(false);
		commits.setEnabled(false);
		historyWorker = VCSHistory.loadPage(current.getGit(), append ? historyHead : null, offset, HISTORY_PAGE_SIZE, page -> {
			if (request != historyRequest || WorkspaceVCS.getVCSWorkspace(workspacePanel.getMCreator().getWorkspace()) != current)
				return;
			if (append && !page.branch().equals(historyBranch)) {
				reloadElements();
				return;
			}
			DefaultTableModel model = (DefaultTableModel) commits.getModel();
			if (!append) {
				model.setRowCount(0);
				cachedCommits.clear();
			}
			historyHead = page.head();
			historyBranch = page.branch();
			switchBranch.setText(L10N.t("workspace.vcs.current_branch", page.branch()));
			for (RevCommit commit : page.commits()) {
				cachedCommits.add(commit);
				model.addRow(new Object[] { commit.abbreviate(7).name(), "<html><b>" + commit.getShortMessage(),
						commit.getAuthorIdent().getName(), Date.from(commit.getAuthorIdent().getWhenAsInstant()) });
			}
			historyLoading = false;
			commits.setEnabled(true);
			loadMore.setVisible(page.hasMore());
			loadMore.setEnabled(true);
			refilterElements();
			if (selected != null)
				for (int i = 0; i < cachedCommits.size(); i++) {
					if (selected.equals(cachedCommits.get(i).getId())) {
						int row = commits.convertRowIndexToView(i);
						if (row >= 0)
							commits.setRowSelectionInterval(row, row);
						break;
					}
				}
		}, ex -> {
			if (request == historyRequest) {
				historyLoading = false;
				commits.setEnabled(true);
				loadMore.setEnabled(true);
				LOG.error("Failed to load commit history", ex);
			}
		});
	}

	@Override public void removeNotify() {
		historyRequest++;
		if (historyWorker != null)
			historyWorker.cancel(true);
		historyLoading = false;
		super.removeNotify();
	}

	@Override public void refilterElements() {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(this::refilterElements);
			return;
		}
		if (WorkspaceVCS.getVCSWorkspace(workspacePanel.getMCreator().getWorkspace()) != null) {
			String search = workspacePanel.getSearchTerm();
			try {
				sorter.setRowFilter(RowFilter.regexFilter(search));
			} catch (PatternSyntaxException ex) {
				sorter.setRowFilter(RowFilter.regexFilter(Pattern.quote(search)));
			}
		}
	}

}
