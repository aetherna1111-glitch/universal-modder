package dev.luminary.fabric.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.fabric.Luminary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The client locked to one server ({@code config/luminary-client.json}: {@code lockToServer}, {@code serverAddress},
 * {@code serverName}). While on: the title screen's Singleplayer button becomes "Play" and connects, Multiplayer and
 * Realms are gone, and any way onto the world list or the server list (a custom menu's buttons included) connects
 * instead of showing it; in the pause menu "Disconnect" becomes "Quit Game", the death screen's "Title Screen" quits
 * too, and a lost connection offers "Reconnect" and "Quit". There is no road back to the main menu while connected.
 * Off, or with an empty address, nothing is touched.
 */
@Environment(EnvType.CLIENT)
public final class ServerLock {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("luminary-client.json");
	private static boolean enabled;
	private static boolean customTitle = true;
	private static String address = "";
	private static String name = "Luminary";

	private ServerLock() {
	}

	static void load() {
		try {
			if (!Files.exists(FILE)) {
				JsonObject d = new JsonObject();
				d.addProperty("lockToServer", false);
				d.addProperty("serverAddress", "");
				d.addProperty("serverName", "Luminary");
				d.addProperty("customTitle", true);
				Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(d) + "\n");
				return;
			}
			JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
			enabled = o.has("lockToServer") && o.get("lockToServer").getAsBoolean();
			address = o.has("serverAddress") ? o.get("serverAddress").getAsString().trim() : "";
			name = o.has("serverName") ? o.get("serverName").getAsString() : "Luminary";
			customTitle = !o.has("customTitle") || o.get("customTitle").getAsBoolean();
			if (enabled && !ServerAddress.isValid(address)) {
				Luminary.LOGGER.warn("[luminary] lockToServer is on but serverAddress '{}' is not valid: the lock stays off", address);
				enabled = false;
			}
		} catch (IOException | RuntimeException e) {
			Luminary.LOGGER.warn("[luminary] could not read {}: the server lock stays off", FILE, e);
			enabled = false;
		}
	}

	static boolean active() {
		return enabled && !address.isEmpty();
	}

	static boolean customTitle() {
		return customTitle;
	}

	static void register() {
		load();
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen && customTitle()) {
				client.execute(() -> {
					if (client.currentScreen instanceof TitleScreen) {
						client.setScreen(new LuminaryTitleScreen());   // Luminary's own main menu in place of vanilla's
					}
				});
				return;
			}
			if (!active()) {
				return;
			}
			if (screen instanceof SelectWorldScreen || screen instanceof MultiplayerScreen) {
				client.execute(() -> connect(client));   // whoever opened it (vanilla or a custom menu): the server, not the list
			} else if (screen instanceof TitleScreen) {
				for (ClickableWidget w : List.copyOf(Screens.getButtons(screen))) {
					if (is(w, "menu.singleplayer")) {
						swap(screen, w, Text.translatable("screen.luminary.lock.play"), b -> connect(client));
					} else if (is(w, "menu.multiplayer") || is(w, "menu.online")) {
						Screens.getButtons(screen).remove(w);
					}
				}
			} else if (screen instanceof GameMenuScreen) {
				for (ClickableWidget w : List.copyOf(Screens.getButtons(screen))) {
					if (is(w, "menu.disconnect") || is(w, "menu.returnToMenu")) {
						swap(screen, w, Text.translatable("menu.quit"), b -> client.scheduleStop());
					}
				}
			} else if (screen instanceof DeathScreen) {
				for (ClickableWidget w : List.copyOf(Screens.getButtons(screen))) {
					if (is(w, "deathScreen.titleScreen")) {
						swap(screen, w, Text.translatable("menu.quit"), b -> client.scheduleStop());
					}
				}
			} else if (screen instanceof DisconnectedScreen) {
				for (ClickableWidget w : List.copyOf(Screens.getButtons(screen))) {
					if (is(w, "gui.toMenu") || is(w, "gui.toTitle")) {
						ButtonWidget again = swap(screen, w, Text.translatable("screen.luminary.lock.reconnect"), b -> connect(client));
						Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("menu.quit"), b -> client.scheduleStop())
								.dimensions(again.getX(), again.getY() + again.getHeight() + 4, again.getWidth(), again.getHeight()).build());
					}
				}
			}
		});
	}


	static boolean is(ClickableWidget w, String key) {
		return w.getMessage().getContent() instanceof TranslatableTextContent t && key.equals(t.getKey());
	}

	/** Replaces {@code old} with a button of the same size and place. */
	static ButtonWidget swap(Screen screen, ClickableWidget old, Text label, ButtonWidget.PressAction action) {
		ButtonWidget fresh = ButtonWidget.builder(label, action).dimensions(old.getX(), old.getY(), old.getWidth(), old.getHeight()).build();
		List<ClickableWidget> buttons = Screens.getButtons(screen);
		int i = buttons.indexOf(old);
		buttons.remove(old);
		buttons.add(i < 0 ? buttons.size() : i, fresh);
		return fresh;
	}

	static void connect(MinecraftClient client) {
		if (client.world != null) {
			return;   // already in the game
		}
		ConnectScreen.connect(new TitleScreen(), client, ServerAddress.parse(address), new ServerInfo(name, address, false), false);
	}
}
