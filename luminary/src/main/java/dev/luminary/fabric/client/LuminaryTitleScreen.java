package dev.luminary.fabric.client;

import dev.luminary.fabric.npc.HolySwordItem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The main menu in Luminary's dress, replacing the vanilla title screen when {@code customTitle} is on in
 * {@code config/luminary-client.json}: deep navy, a dawn glow on the horizon, motes of light drifting up, the name in
 * the blade's shimmering gold, and the trade screen's buttons. With the server lock on there is one way in: "Play";
 * without it, the vanilla Singleplayer and Multiplayer screens are a button away.
 */
@Environment(EnvType.CLIENT)
public final class LuminaryTitleScreen extends Screen {
	private static final int MOTES = 48;

	public LuminaryTitleScreen() {
		super(Text.literal("Luminary"));
	}

	@Override
	protected void init() {
		int w = 200, x = (width - w) / 2, y = height / 2 + 8;
		if (ServerLock.active()) {
			addDrawableChild(new TradeScreen.ThemeButton(x, y, w, 20, Text.translatable("screen.luminary.lock.play"), b -> ServerLock.connect(client), true));
		} else {
			addDrawableChild(new TradeScreen.ThemeButton(x, y, w, 20, Text.translatable("menu.singleplayer"), b -> client.setScreen(new SelectWorldScreen(this)), true));
			addDrawableChild(new TradeScreen.ThemeButton(x, y + 24, w, 20, Text.translatable("menu.multiplayer"), b -> client.setScreen(new MultiplayerScreen(this)), false));
			y += 24;
		}
		addDrawableChild(new TradeScreen.ThemeButton(x, y + 28, w / 2 - 2, 20, Text.translatable("menu.options"), b -> client.setScreen(new OptionsScreen(this, client.options)), false));
		addDrawableChild(new TradeScreen.ThemeButton(x + w / 2 + 2, y + 28, w / 2 - 2, 20, Text.translatable("menu.quit"), b -> client.scheduleStop(), false));
	}

	@Override
	public void render(DrawContext c, int mouseX, int mouseY, float delta) {
		long t = System.currentTimeMillis();
		// the sky: navy above, a dawn glow at the horizon line
		c.fillGradient(0, 0, width, height, 0xFF070E1E, 0xFF101C36);
		int horizon = height * 5 / 6;   // below the buttons
		c.fillGradient(0, horizon - 60, width, horizon, 0x00000000, 0x33E2B857);
		c.fillGradient(0, horizon, width, height, 0x22E2B857, 0x00000000);
		c.fill(0, horizon, width, horizon + 1, 0x80E2B857);
		// motes of light drifting upwards, each on its own slow path
		for (int i = 0; i < MOTES; i++) {
			double seed = i * 7919.0;
			double speed = 6000 + (i % 7) * 1700;
			double phase = ((t + seed * 13) % speed) / speed;
			int x = (int) ((seed * 0.618) % width + Math.sin(t / 2500.0 + i) * 6);
			int y = (int) (height - phase * (height + 20));
			int alpha = (int) (Math.sin(phase * Math.PI) * 160);
			int size = i % 5 == 0 ? 2 : 1;
			c.fill(x, y, x + size, y + size, (alpha << 24) | 0xFFE9A6);
		}
		// the name, large, in the blade's shimmer; the line beneath in muted gold
		c.getMatrices().push();
		c.getMatrices().translate(width / 2.0, height / 2.0 - 70, 0);
		c.getMatrices().scale(4f, 4f, 1f);
		Text name = HolySwordItem.shimmer("LUMINARY");
		c.drawText(textRenderer, name, -textRenderer.getWidth(name) / 2, 0, 0xFFFFFF, true);
		c.getMatrices().pop();
		c.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.luminary.title.subtitle").styled(s -> s.withColor(TradeScreen.GOLD_DIM)), width / 2, height / 2 - 26, TradeScreen.GOLD_DIM);
		// frame lines like the trade screen's, and the version in the corner
		c.fill(width / 2 - 120, height / 2 - 12, width / 2 + 120, height / 2 - 11, 0x60E2B857);
		String version = FabricLoader.getInstance().getModContainer("luminary").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		c.drawTextWithShadow(textRenderer, Text.literal("Luminary " + version + " · Minecraft 1.20.1").formatted(Formatting.DARK_GRAY), 4, height - 12, 0xFF6B7690);
		super.render(c, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
