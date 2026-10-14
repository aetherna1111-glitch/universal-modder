package dev.luminary.fabric.client;

import dev.luminary.fabric.npc.GearViewScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** The gear screen: the vanilla inventory frame, the NPC's body where the player's would be, extra slot frames for the sword, the shield and the bag. */
@Environment(EnvType.CLIENT)
public final class GearViewScreen extends HandledScreen<GearViewScreenHandler> {
	private static final Identifier INVENTORY = new Identifier("textures/gui/container/inventory.png");

	public GearViewScreen(GearViewScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.titleY = 6;
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		int x = this.x, y = this.y;
		context.drawTexture(INVENTORY, x, y, 0, 0, backgroundWidth, backgroundHeight);
		// cover the crafting area and draw the bag frames there, plus a frame for the sword (right of the body)
		context.fill(x + 96, y + 7, x + 168, y + 66, 0xFFC6C6C6);
		for (int i = 0; i < 9; i++) {
			context.drawTexture(INVENTORY, x + 97 + (i % 3) * 18, y + 17 + (i / 3) * 18, 7, 141, 18, 18);
		}
		context.drawTexture(INVENTORY, x + 76, y + 25, 7, 141, 18, 18);
		if (handler.npc() != null) {
			// 1.20.1 signature: (context, x, y, size, mouseX offset, mouseY offset, entity); the body looks towards the cursor
			InventoryScreen.drawEntity(context, x + 51, y + 75, 30, (float) (x + 51) - mouseX, (float) (y + 75 - 50) - mouseY, handler.npc());
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
		drawMouseoverTooltip(context, mouseX, mouseY);
	}
}
