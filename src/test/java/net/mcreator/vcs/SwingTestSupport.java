package net.mcreator.vcs;

import net.mcreator.Launcher;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.init.UIRES;
import net.mcreator.ui.laf.themes.Theme;
import net.mcreator.ui.laf.themes.ThemeManager;
import net.mcreator.util.MCreatorVersionNumber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public abstract class SwingTestSupport {

	@BeforeAll static void initializeUI() throws Exception {
		Properties properties = new Properties();
		try (InputStream config = Launcher.class.getResourceAsStream("/mcreator.conf")) {
			properties.load(Objects.requireNonNull(config));
		}
		Launcher.version = new MCreatorVersionNumber(properties);
		Field translations = L10N.class.getDeclaredField("rb");
		translations.setAccessible(true);
		translations.set(null, ResourceBundle.getBundle("lang.texts"));
		Field imageCache = UIRES.class.getDeclaredField("THEME_CACHE");
		imageCache.setAccessible(true);
		@SuppressWarnings("unchecked") Map<String, ImageIcon> icons = (Map<String, ImageIcon>) imageCache.get(null);
		for (String icon : new String[] { "18px.ok.png", "18px.remove.png", "18px.warning.png" })
			icons.put(icon, new ImageIcon());
		onSwingThread(() -> {
			Field theme = ThemeManager.class.getDeclaredField("CURRENT_THEME");
			theme.setAccessible(true);
			theme.set(null, new Theme());
			return null;
		});
	}

	@AfterEach void closeWindows() throws Exception {
		onSwingThread(() -> {
			for (Window window : Window.getWindows())
				window.dispose();
			return null;
		});
	}

	protected static <T> T onSwingThread(Callable<T> task) throws Exception {
		FutureTask<T> result = new FutureTask<>(task);
		SwingUtilities.invokeAndWait(result);
		return result.get();
	}
}
