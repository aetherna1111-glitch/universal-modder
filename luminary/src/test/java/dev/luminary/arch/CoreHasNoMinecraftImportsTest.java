package dev.luminary.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture rule: {@code dev.luminary.core} must stay free of Minecraft and Fabric so it is testable without a
 * game and portable across Minecraft versions. Gson is allowed (it ships with the game).
 */
class CoreHasNoMinecraftImportsTest {
	private static final List<String> FORBIDDEN = List.of("import net.minecraft.", "import net.fabricmc.", "import dev.luminary.fabric.",
			"import com.mojang.", "import org.spongepowered.");

	@Test
	void corePackageDoesNotImportMinecraftOrFabric() throws IOException {
		Path core = Paths.get("src/main/java/dev/luminary/core");
		assertTrue(Files.isDirectory(core), "run from the project root: " + core.toAbsolutePath());
		List<String> violations = new ArrayList<>();
		try (Stream<Path> files = Files.walk(core)) {
			for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
				for (String line : Files.readAllLines(p)) {
					for (String f : FORBIDDEN) {
						if (line.startsWith(f)) {
							violations.add(p + ": " + line.trim());
						}
					}
				}
			}
		}
		assertTrue(violations.isEmpty(), "core imports Minecraft/Fabric:\n" + String.join("\n", violations));
	}
}
