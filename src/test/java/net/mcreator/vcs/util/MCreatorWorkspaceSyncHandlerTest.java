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

import net.mcreator.generator.Generator;
import net.mcreator.generator.GeneratorTemplate;
import net.mcreator.generator.template.TemplateGeneratorException;
import net.mcreator.generator.usercode.UserCodeProcessor;
import net.mcreator.preferences.PreferencesManager;
import net.mcreator.preferences.data.PreferencesData;
import net.mcreator.ui.init.L10N;
import net.mcreator.vcs.SwingTestSupport;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.WorkspaceFileManager;
import net.mcreator.workspace.settings.WorkspaceSettings;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class MCreatorWorkspaceSyncHandlerTest extends SwingTestSupport {

	private static final String MODS_PATH = "src/main/resources/META-INF/neoforge.mods.toml";
	@TempDir Path repository;

	@BeforeAll static void initializePreferences() {
		PreferencesManager.PREFERENCES = new PreferencesData();
		if (net.mcreator.plugin.PluginLoader.INSTANCE == null)
			net.mcreator.plugin.PluginLoader.initInstance();
		if (net.mcreator.element.ModElementType.UNKNOWN == null)
			net.mcreator.element.ModElementType.UNKNOWN = new net.mcreator.element.ModElementType<>("unknown", null,
					(a, b, c) -> null, net.mcreator.element.GeneratableElement.Unknown.class);
	}

	@Test void mergingOneLanguageDoesNotRemoveTranslationsFromOtherLanguages() throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, "managed.txt", null)) {
			workspace.getLanguageMap().get("en_us").put("target", "English");
			workspace.getLanguageMap().put("it_it", new java.util.LinkedHashMap<>(Map.of("target", "Italian")));
			String base = WorkspaceFileManager.gson.toJson(workspace, Workspace.class);
			workspace.getLanguageMap().get("it_it").remove("target");
			workspace.getLanguageMap().get("it_it").put("local", "L");
			Workspace remote = WorkspaceFileManager.gson.fromJson(base, Workspace.class);
			remote.getLanguageMap().get("it_it").put("remote", "R");
			FileSyncHandle handle = workspaceHandle(base, WorkspaceFileManager.gson.toJson(workspace, Workspace.class),
					WorkspaceFileManager.gson.toJson(remote));
			assertFalse(new MCreatorWorkspaceSyncHandler(null).handleSync(git, workspace, List.of(handle), false));
			assertEquals("English", workspace.getLanguageMap().get("en_us").get("target"));
			assertFalse(workspace.getLanguageMap().get("it_it").containsKey("target"));
			assertEquals(Map.of("local", "L", "remote", "R"), workspace.getLanguageMap().get("it_it"));
		}
	}

	private FileSyncHandle workspaceHandle(String base, String local, String remote) {
		FileSyncHandle handle = new FileSyncHandle("test.mcreator");
		handle.setBaseBytes(base.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		handle.setLocalBytes(local.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		handle.setRemoteBytes(remote.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		handle.setLocalPath("test.mcreator");
		handle.setRemotePath("test.mcreator");
		handle.setUnmerged(true);
		return handle;
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cleanWorkspaceMergeReloadsModelBeforeRegeneration(boolean missingGenerator) throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, "managed.txt", null)) {
			String base = WorkspaceFileManager.gson.toJson(workspace, Workspace.class);
			Workspace remote = WorkspaceFileManager.gson.fromJson(base, Workspace.class);
			remote.getWorkspaceSettings().setAuthor("Remote author");
			if (missingGenerator)
				remote.getWorkspaceSettings().setCurrentGenerator("missing-generator");
			String after = WorkspaceFileManager.gson.toJson(remote);
			Files.writeString(workspace.getFileManager().getWorkspaceFile().toPath(), after);
			FileSyncHandle handle = workspaceHandle(base, base, after);
			handle.setUnmerged(false);
			if (missingGenerator) {
				assertThrows(IOException.class, () -> new MCreatorWorkspaceSyncHandler(null).handleSync(git, workspace, List.of(handle), false));
				assertEquals(0, workspace.testGenerator.regenerations);
			} else {
				assertFalse(new MCreatorWorkspaceSyncHandler(null).handleSync(git, workspace, List.of(handle), false));
				assertEquals("Remote author", workspace.testGenerator.lastAuthor);
				assertEquals("Remote author", workspace.getWorkspaceSettings().getAuthor());
			}
			assertEquals(after, Files.readString(workspace.getFileManager().getWorkspaceFile().toPath()));
		}
	}

	@Test void failedWorkspaceSaveRestoresPreviousFileAndKeepsModelForRetry() throws Exception {
		try (TestWorkspace workspace = new TestWorkspace(repository, "managed.txt", null)) {
			Path file = workspace.getFileManager().getWorkspaceFile().toPath();
			String original = WorkspaceFileManager.gson.toJson(workspace, Workspace.class);
			Files.writeString(file, original);
			workspace.getWorkspaceSettings().setAuthor("pending work");
			workspace.getFileManager().setDataSavedListener(() -> {
				try { Files.writeString(file, "{partial"); }
				catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
			});
			assertThrows(IOException.class, () -> CheckedWorkspaceIO.save(workspace));
			assertEquals(original, Files.readString(file));
			assertEquals("pending work", workspace.getWorkspaceSettings().getAuthor());
			workspace.getFileManager().setDataSavedListener(null);
			CheckedWorkspaceIO.save(workspace);
			assertTrue(Files.readString(file).contains("pending work"));
		}
	}

	@Test void fileDeletionDoesNotRemoveDirectoriesOrFilesOutsideWorkspace() throws Exception {
		Path root = repository.resolve("workspace");
		Files.createDirectories(root);
		try (TestWorkspace workspace = new TestWorkspace(root, "managed.txt", null)) {
			Path directory = root.resolve("conflict.txt");
			Files.createDirectories(directory);
			Files.writeString(directory.resolve("untracked.txt"), "keep nested work");
			Path outside = repository.resolve("outside.txt");
			Files.writeString(outside, "keep outside work");
			assertThrows(IOException.class, () -> CheckedWorkspaceIO.delete(workspace, directory));
			assertThrows(IOException.class, () -> CheckedWorkspaceIO.delete(workspace, outside));
			assertEquals("keep nested work", Files.readString(directory.resolve("untracked.txt")));
			assertEquals("keep outside work", Files.readString(outside));
			Path normal = root.resolve("removed.txt");
			Files.writeString(normal, "delete selected file");
			CheckedWorkspaceIO.delete(workspace, normal);
			assertFalse(Files.exists(normal));
		}
	}

	@Test void workspaceConflictPreservesOneSidedSettingsMetadataAndChangedVariables() throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, "managed.txt", null)) {
			var variable = new net.mcreator.workspace.elements.VariableElement("value");
			variable.setValue(0d);
			workspace.addVariableElement(variable);
			String base = WorkspaceFileManager.gson.toJson(workspace, Workspace.class);
			workspace.getWorkspaceSettings().setAuthor("Local author");
			workspace.putMetadata("local", "keep local metadata");
			Workspace remote = WorkspaceFileManager.gson.fromJson(base, Workspace.class);
			remote.getVariableElementByName("value").setValue(42);
			remote.putMetadata("remote", "keep remote metadata");
			FileSyncHandle handle = workspaceHandle(base, WorkspaceFileManager.gson.toJson(workspace, Workspace.class),
					WorkspaceFileManager.gson.toJson(remote));
			assertFalse(new MCreatorWorkspaceSyncHandler(null).handleSync(git, workspace, List.of(handle), false));
			assertEquals("Local author", workspace.getWorkspaceSettings().getAuthor());
			assertEquals(42d, ((Number) workspace.getVariableElementByName("value").getValue()).doubleValue());
			assertEquals("keep local metadata", workspace.getMetadata("local"));
			assertEquals("keep remote metadata", workspace.getMetadata("remote"));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void metadataConflictsUseExplicitChoiceAndKeepIndependentValues(boolean chooseRemote) throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, "managed.txt", null)) {
			workspace.putMetadata("conflict", "base");
			String base = WorkspaceFileManager.gson.toJson(workspace, Workspace.class);
			workspace.putMetadata("conflict", "local");
			workspace.putMetadata("localOnly", "L");
			Workspace remote = WorkspaceFileManager.gson.fromJson(base, Workspace.class);
			remote.putMetadata("conflict", "remote");
			remote.putMetadata("remoteOnly", "R");
			FileSyncHandle handle = workspaceHandle(base, WorkspaceFileManager.gson.toJson(workspace, Workspace.class),
					WorkspaceFileManager.gson.toJson(remote));
			Timer driver = onSwingThread(() -> {
				Timer timer = new Timer(10, event -> {
					for (Window window : Window.getWindows()) {
						if (window instanceof JDialog dialog && dialog.isShowing()
								&& dialog.getTitle().equals(L10N.t("dialog.vcs.file_merge_manual_required"))) {
							((Timer) event.getSource()).stop();
							button(dialog, chooseRemote ? "dialog.vcs.file_merge_all_remote" : "dialog.vcs.file_merge_all_local").doClick();
							button(dialog, "dialog.vcs.file_merge_finish").doClick();
						}
					}
				});
				timer.start();
				return timer;
			});
			try {
				assertTrue(new MCreatorWorkspaceSyncHandler(null).handleSync(git, workspace, List.of(handle), false));
				assertEquals(chooseRemote ? "remote" : "local", workspace.getMetadata("conflict"));
				assertEquals("L", workspace.getMetadata("localOnly"));
				assertEquals("R", workspace.getMetadata("remoteOnly"));
			} finally {
				onSwingThread(() -> { driver.stop(); return null; });
			}
		}
	}

	@Test void virtualMutationsNeedNoHistoryAndDoNotDeleteRealSoundFiles() throws Exception {
		try (TestWorkspace workspace = new TestWorkspace(repository, "managed.txt", null)) {
			Workspace data = WorkspaceFileManager.gson.fromJson(
					WorkspaceFileManager.gson.toJson(workspace, Workspace.class), Workspace.class);
			List<Generator> generators = new ArrayList<>();
			Path soundFile = repository.resolve("keep.ogg");
			Files.writeString(soundFile, "keep sound data");
			try {
				WorkspaceSnapshot snapshot = new WorkspaceSnapshot(workspace, data, generators);
				var element = new net.mcreator.workspace.elements.ModElement(workspace, "NewElement",
						net.mcreator.element.ModElementType.UNKNOWN);
				snapshot.addModElement(element);
				snapshot.addLanguage("fr_fr", new java.util.LinkedHashMap<>(Map.of("key", "French")));
				var variable = new net.mcreator.workspace.elements.VariableElement("value");
				variable.setValue(12);
				snapshot.addVariableElement(variable);
				var sound = new net.mcreator.workspace.elements.SoundElement("sound",
						List.of(repository.resolve("keep").toString()), "master", null);
				snapshot.addSoundElement(sound);
				snapshot.removeSoundElement(sound);
				Workspace stored = snapshot.storedData();
				assertNotNull(stored.getModElementByName("NewElement"));
				assertNotNull(stored.getVariableElementByName("value"));
				assertEquals("French", stored.getLanguageMap().get("fr_fr").get("key"));
				assertTrue(workspace.getModElements().isEmpty());
				assertFalse(workspace.getLanguageMap().containsKey("fr_fr"));
				assertEquals("keep sound data", Files.readString(soundFile));
			} finally {
				generators.forEach(Generator::close);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void virtualWorkspaceWatchersAreClosedAfterSuccessAndFailure(boolean fail) throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, MODS_PATH, "#")) {
			Set<Thread> before = Thread.getAllStackTraces().keySet();
			for (int i = 0; i < 4; i++) {
				FileSyncHandle handle = new FileSyncHandle("test.mcreator");
				handle.setLocalPath("test.mcreator");
				handle.setRemotePath("test.mcreator");
				String json = WorkspaceFileManager.gson.toJson(workspace, Workspace.class);
				if (fail)
					json = json.replace("\"children\": []", "\"children\": null")
							.replace("\"children\":[]", "\"children\":null");
				handle.setRemoteBytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				if (fail)
					assertThrows(NullPointerException.class, () -> new MCreatorWorkspaceSyncHandler(null)
							.handleSync(git, workspace, List.of(handle), true));
				else
					assertFalse(new MCreatorWorkspaceSyncHandler(null).handleSync(git, workspace, List.of(handle), true));
			}
			for (Thread thread : Thread.getAllStackTraces().keySet()) {
				if (!before.contains(thread) && thread.getName().equals("File Watcher Thread")) {
					thread.join(1000);
					assertFalse(thread.isAlive(), "Virtual workspace watcher must terminate");
				}
			}
			Field closed = net.mcreator.io.FileWatcher.class.getDeclaredField("closed");
			closed.setAccessible(true);
			assertFalse(closed.getBoolean(workspace.getGenerator().getFileWatcher()));
			assertEquals(0, workspace.testGenerator.regenerations);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cleanSyncPreservesUserCodeAndRegeneratesManagedContent(boolean changeRemote) throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, MODS_PATH, "#")) {
			writeMods(mods("base", ""));
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			if (changeRemote)
				writeMods(mods("remote", "customDependency = \"remote\"\n"));
			else
				Files.writeString(repository.resolve("remote.txt"), "remote\n");
			RevCommit remote = commit(git, "remote");
			git.checkout().setName("local").call();
			if (!changeRemote)
				writeMods(mods("local", "customDependency = \"local\"\n"));
			else
				Files.writeString(repository.resolve("local.txt"), "local\n");
			RevCommit local = commit(git, "local");

			var result = sync(git, workspace, local, remote, false);

			assertFalse(result.requiredUserAction());
			assertEquals(Map.of("dependencies configuration",
					"customDependency = \"" + (changeRemote ? "remote" : "local") + "\"\n"),
					UserCodeProcessor.getUserCodeBlocks(Files.readString(repository.resolve(MODS_PATH)), "#"));
			assertTrue(Files.readString(repository.resolve(MODS_PATH)).startsWith("generated = \"current\""));
			assertEquals(1, workspace.testGenerator.regenerations);
			assertTrue(git.status().call().getConflicting().isEmpty());
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void conflictingUserCodeWaitsForSelectionBeforeRegeneration(boolean chooseRemote) throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, MODS_PATH, "#")) {
			writeMods(mods("base", "dependency = \"base\"\n"));
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			writeMods(mods("remote", "dependency = \"remote\"\n"));
			RevCommit remote = commit(git, "remote");
			git.checkout().setName("local").call();
			writeMods(mods("local", "dependency = \"local\"\n"));
			RevCommit local = commit(git, "local");
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Timer driver = onSwingThread(() -> {
				Timer timer = new Timer(10, event -> {
					for (Window window : Window.getWindows()) {
						if (!(window instanceof JDialog dialog) || !window.isShowing()
								|| !dialog.getTitle().equals(L10N.t("dialog.vcs.file_merge_manual_required")))
							continue;
						((Timer) event.getSource()).stop();
						try {
							assertEquals(0, workspace.testGenerator.regenerations);
							assertFalse(git.status().call().getConflicting().isEmpty());
							button(dialog, chooseRemote ? "dialog.vcs.file_merge_all_remote"
									: "dialog.vcs.file_merge_all_local").doClick();
							button(dialog, "dialog.vcs.file_merge_finish").doClick();
						} catch (Throwable ex) {
							failure.set(ex);
						} finally {
							dialog.dispose();
						}
					}
				});
				timer.start();
				return timer;
			});
			try {
				var result = CompletableFuture.supplyAsync(() -> {
					try {
						return sync(git, workspace, local, remote, false);
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				}).get(15, TimeUnit.SECONDS);
				if (failure.get() != null)
					throw new AssertionError(failure.get());
				assertTrue(result.requiredUserAction());
				assertEquals(Map.of("dependencies configuration",
						"dependency = \"" + (chooseRemote ? "remote" : "local") + "\"\n"),
						UserCodeProcessor.getUserCodeBlocks(Files.readString(repository.resolve(MODS_PATH)), "#"));
				assertEquals(1, workspace.testGenerator.regenerations);
				assertFalse(Files.readString(repository.resolve(MODS_PATH)).contains("<<<<<<<"));
				assertTrue(git.status().call().getConflicting().isEmpty());
			} finally {
				onSwingThread(() -> {
					driver.stop();
					return null;
				});
			}
		}
	}

	@Test void gitMergesIndependentUserBlocksBeforeRegeneration() throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, MODS_PATH, "#")) {
			writeMods(mods("current", "dependency = \"base\"\n") + mixins("config = \"base\"\n"));
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			writeMods(mods("current", "dependency = \"base\"\n") + mixins("config = \"remote\"\n"));
			RevCommit remote = commit(git, "remote");
			git.checkout().setName("local").call();
			writeMods(mods("current", "dependency = \"local\"\n") + mixins("config = \"base\"\n"));
			RevCommit local = commit(git, "local");

			var result = sync(git, workspace, local, remote, false);

			assertFalse(result.requiredCustomMergeHandler());
			assertFalse(result.requiredUserAction());
			assertEquals(Map.of("dependencies configuration", "dependency = \"local\"\n",
					"custom mixins", "config = \"remote\"\n"),
					UserCodeProcessor.getUserCodeBlocks(Files.readString(repository.resolve(MODS_PATH)), "#"));
			assertEquals(1, workspace.testGenerator.regenerations);
			assertTrue(git.status().call().getConflicting().isEmpty());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { MODS_PATH, "src/main/java/TestMod.java", "src/main/resources/script.js" })
	void dryRunReportsUserCodeConflictsWithoutChangingFilesOrIndex(String path) throws Exception {
		String comment = path.equals(MODS_PATH) ? "#" : "//";
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, path,
				path.equals(MODS_PATH) ? "#" : null)) {
			Path file = repository.resolve(path);
			Files.createDirectories(file.getParent());
			Files.writeString(file, mods("base", "base\n").replace("#", comment));
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			Files.writeString(file, mods("remote", "remote\n").replace("#", comment));
			RevCommit remote = commit(git, "remote");
			git.checkout().setName("local").call();
			Files.writeString(file, mods("local", "local\n").replace("#", comment));
			RevCommit local = commit(git, "local");
			byte[] before = Files.readAllBytes(file);

			var result = sync(git, workspace, local, remote, true);

			assertTrue(result.requiredUserAction());
			assertEquals(0, workspace.testGenerator.regenerations);
			assertArrayEquals(before, Files.readAllBytes(file));
			assertEquals(local, git.getRepository().resolve(Constants.HEAD));
			assertTrue(git.status().call().isClean());
		}
	}

	@Test void generatorFailureDoesNotMarkUnmergedFilesResolved() throws Exception {
		try (Git git = createRepository(); TestWorkspace workspace = new TestWorkspace(repository, "base.txt", null)) {
			Files.writeString(repository.resolve("base.txt"), "base\n");
			commit(git, "base");
			git.checkout().setCreateBranch(true).setName("remote").call();
			Files.writeString(repository.resolve("base.txt"), "remote\n");
			RevCommit remote = commit(git, "remote");
			git.checkout().setName("local").call();
			Files.writeString(repository.resolve("base.txt"), "local\n");
			RevCommit local = commit(git, "local");
			workspace.testGenerator.failGeneration = true;

			assertInstanceOf(TemplateGeneratorException.class,
					assertThrows(IOException.class, () -> sync(git, workspace, local, remote, false)).getCause());

			assertEquals(java.util.Set.of("base.txt"), git.status().call().getConflicting());
			assertEquals(local, git.getRepository().resolve(Constants.HEAD));
		}
	}

	private SyncTwoRefsWithMerge.SyncResult sync(Git git, TestWorkspace workspace, RevCommit local, RevCommit remote,
			boolean dryRun) throws Exception {
		return SyncTwoRefsWithMerge.sync(git, local, remote,
				(repositoryGit, conflicts, handles, testOnly) -> new MCreatorWorkspaceSyncHandler(null)
						.handleSync(repositoryGit, workspace, handles, testOnly),
				dryRun ? null : () -> git.merge().include(remote).call(), dryRun);
	}

	private Git createRepository() throws Exception {
		Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("local").call();
		git.getRepository().getConfig().setString("user", null, "name", "Test");
		git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
		return git;
	}

	private RevCommit commit(Git git, String message) throws Exception {
		git.add().addFilepattern(".").call();
		return git.commit().setAll(true).setMessage(message).call();
	}

	private void writeMods(String code) throws IOException {
		Path path = repository.resolve(MODS_PATH);
		Files.createDirectories(path.getParent());
		Files.writeString(path, code);
	}

	private static String mods(String generated, String userCode) {
		return "generated = \"" + generated + "\"\n\n# Start of user code block dependencies configuration\n"
				+ userCode + "# End of user code block dependencies configuration\n";
	}

	private static String mixins(String userCode) {
		return "\n\n\n\n# Start of user code block custom mixins\n" + userCode
				+ "# End of user code block custom mixins\n";
	}

	private JButton button(Container container, String key) {
		return buttons(container).stream().filter(button -> button.getText().equals(L10N.t(key))).findFirst()
				.orElseThrow();
	}

	private List<JButton> buttons(Container container) {
		List<JButton> result = new ArrayList<>();
		for (Component component : container.getComponents()) {
			if (component instanceof JButton button)
				result.add(button);
			if (component instanceof Container child)
				result.addAll(buttons(child));
		}
		return result;
	}

	private static class TestWorkspace extends Workspace {

		private final transient TestGenerator testGenerator;

		TestWorkspace(Path root, String path, String comment) throws Exception {
			super(new WorkspaceSettings("test"));
			getWorkspaceSettings().setCurrentGenerator("test");
			var constructor = WorkspaceFileManager.class.getDeclaredConstructor(File.class, Workspace.class);
			constructor.setAccessible(true);
			fileManager = constructor.newInstance(root.resolve("test.mcreator").toFile(), this);
			generator = testGenerator = new TestGenerator(this, root.resolve(path).toFile(), comment);
		}

		@Override public void close() {
			try {
				Field executor = WorkspaceFileManager.class.getDeclaredField("dataSaveExecutor");
				executor.setAccessible(true);
				((ScheduledExecutorService) executor.get(fileManager)).shutdownNow();
				generator.close();
			} catch (ReflectiveOperationException ex) {
				throw new RuntimeException(ex);
			}
		}
	}

	private static class TestGenerator extends Generator {

		private final GeneratorTemplate template;
		private int regenerations;
		private boolean failGeneration;
		private String lastAuthor;

		TestGenerator(Workspace workspace, File file, String comment) {
			super(workspace);
			template = GeneratorTemplate.fromFile(file,
					comment == null ? Map.of() : Map.of("usercodeComment", comment));
		}

		@Override public List<GeneratorTemplate> getModBaseGeneratorTemplatesList() {
			return List.of(template);
		}

		@Override public File getLangFilesRoot() {
			return new File(getWorkspace().getWorkspaceFolder(), "lang");
		}

		@Override public void generateBase(boolean formatAndOrganiseImports) throws TemplateGeneratorException {
			regenerations++;
			lastAuthor = getWorkspace().getWorkspaceSettings().getAuthor();
			if (failGeneration)
				throw new TemplateGeneratorException("test generation failure");
			generateFiles(List.of(template.toGeneratorFile(mods("current", "") + mixins(""))), false);
		}
	}
}
