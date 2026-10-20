package net.mcreator.vcs;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LanguageResourcesTest {

	static Stream<Path> translations() throws IOException {
		try (var files = Files.list(Path.of("src/main/resources/lang"))) {
			return files.filter(path -> path.getFileName().toString().endsWith(".properties")).sorted().toList().stream();
		}
	}

	@ParameterizedTest @MethodSource("translations")
	void everyLanguageProvidesAllMessagesAndTheirArguments(Path path) throws IOException {
		Properties base = load(Path.of("src/main/resources/lang/texts.properties"));
		Properties translated = load(path);
		assertEquals(base.stringPropertyNames(), translated.stringPropertyNames());
		Pattern argument = Pattern.compile("\\{(\\d+)(?:,[^{}]+)?}");
		for (String key : base.stringPropertyNames()) {
			String value = translated.getProperty(key);
			assertFalse(value.isBlank(), key);
			MessageFormat format = assertDoesNotThrow(() -> new MessageFormat(value), key);
			String rendered = format.format(new Object[] { "__0__", "__1__", "__2__" });
			var matcher = argument.matcher(base.getProperty(key));
			while (matcher.find())
				assertTrue(rendered.contains("__" + matcher.group(1) + "__"), key + ": " + value);
		}
	}

	private Properties load(Path path) throws IOException {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return properties;
	}
}
