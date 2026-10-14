package dev.luminary.fabric.client;

import dev.luminary.fabric.npc.LuminArmorItem;
import dev.luminary.fabric.npc.LuminaryItems;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcEntities;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;

import java.io.File;
import java.util.List;

/**
 * Developer aid, off unless {@code -Dluminary.screenshot=<file>} is set: on the title screen, opens the trade
 * screen with a sample snapshot (no server needed), writes one screenshot and quits. Used to show the screen's
 * look from a headless client; nothing in the game ever calls this.
 */
@Environment(EnvType.CLIENT)
final class TradeScreenShot {
	private static int ticks;

	static void registerIfRequested() {
		String out = System.getProperty("luminary.screenshot");
		if (out == null || out.isEmpty()) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.getOverlay() != null || (!(client.currentScreen instanceof TitleScreen) && !(client.currentScreen instanceof TradeScreen))) {
				return;   // the splash overlay must be gone before anything is visible
			}
			ticks++;
			if (ticks == 20) {
				TradeScreen.portraitOverride = portrait();
				client.setScreen(new TradeScreen(sample()));
			}
			if (ticks == 80 && client.currentScreen instanceof TradeScreen) {
				shoot(client, out);
			}
			if (ticks == 90 && client.currentScreen instanceof TradeScreen screen) {
				screen.devSwitch(TradeScreen.Tab.SHOP);
			}
			if (ticks == 110 && client.currentScreen instanceof TradeScreen) {
				shoot(client, out.replace(".png", "_shop.png"));
			}
			if (ticks == 120) {
				client.scheduleStop();
			}
		});
	}

	private static void shoot(MinecraftClient client, String out) {
		File f = new File(out);
		ScreenshotRecorder.saveScreenshot(f.getAbsoluteFile().getParentFile(), f.getName(), client.getFramebuffer(), t -> {
		});
	}

	private static LuminaryNpcEntity portrait() {
		try {
			LuminaryNpcEntity npc = new LuminaryNpcEntity(NpcEntities.NPC, null);
			NbtCompound nbt = new NbtCompound();
			NbtList armour = new NbtList();
			for (LuminArmorItem piece : List.of(LuminaryItems.VESTMENT_BOOTS, LuminaryItems.VESTMENT_LEGGINGS, LuminaryItems.VESTMENT_CHESTPLATE, LuminaryItems.VESTMENT_HELMET)) {
				armour.add(LuminArmorItem.unbound(piece).writeNbt(new NbtCompound()));
			}
			nbt.put("ArmorItems", armour);
			npc.readCustomDataFromNbt(nbt);
			return npc;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static TradeScreen.Snapshot sample() {
		List<TradeScreen.Offer> offers = List.of(
				new TradeScreen.Offer(new ItemStack(LuminaryItems.LIGHTSTONE_DUST, 8), 10, false, false),
				new TradeScreen.Offer(new ItemStack(net.minecraft.item.Items.BLAZE_ROD), 20, false, false),
				new TradeScreen.Offer(new ItemStack(net.minecraft.item.Items.ENCHANTED_BOOK), 25, false, false),
				new TradeScreen.Offer(new ItemStack(LuminaryItems.ENVOY_SEAL), 80, true, false));
		List<TradeScreen.QuestRow> quests = List.of(
				row("night_lich", 3, 1, 1, 10, false, false, "bosses_of_mass_destruction:lich"),
				row("withered_king", 2, 1, 1, 12, false, false, "soulsweapons:accursed_lord_boss"),
				row("hand_of_the_nether", 1, 0, 1, 12, false, false, "bosses_of_mass_destruction:gauntlet"),
				row("fallen_dragon", 0, 0, 1, 15, false, false, "minecraft:ender_dragon"),
				row("three_essences", 1, 1, 3, 30, false, false, "soulsweapons:chaos_monarch"),
				row("trial_of_the_envoy", 4, 0, 5, 0, true, true, "archon:null"));
		return new TradeScreen.Snapshot(-1, Text.literal("Люмин"), "Что тебе нужно? Я плачу своими очками за то, что считаю важным, и продаю то, что мне самому дорого.", "",
				22, 2, 0, false, -35, 12, 30, "WARY", false, -1.5, 0.26, offers, quests);
	}

	private static TradeScreen.QuestRow row(String name, int state, int progress, int required, int points, boolean special, boolean reset, String entity) {
		String base = "quest.luminary.quest.lumin." + name;
		return new TradeScreen.QuestRow("luminary:quest/lumin/" + name, base + ".title", base + ".desc", state, progress, required, points, special, reset,
				List.of(new TradeScreen.Kill(entity, required, progress)));
	}
}
