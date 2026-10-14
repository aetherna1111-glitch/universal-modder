package dev.luminary.fabric.client;

import dev.luminary.fabric.npc.TradeManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Lumin's dialogue screen: his portrait and name on the left with his points, a line from him underneath, and on the
 * right the tabs of the conversation (the shop, his tasks, a look at his gear), in the launcher's dark-navy-and-gold
 * dress. Everything shown comes from the server's snapshot; every button sends one action and waits for the next
 * snapshot, so the client never decides anything.
 */
@Environment(EnvType.CLIENT)
public final class TradeScreen extends Screen {
	// palette (the launcher: deep navy, warm gold, parchment text)
	public static final int BG = 0xF00B1426, PANEL = 0xFF15223D, PANEL_LIGHT = 0xFF1C2D4F, GOLD = 0xFFE2B857, GOLD_DIM = 0xFF8C7340, TEXT = 0xFFEFE7D3, MUTED = 0xFF9AA6C0, GREEN = 0xFF7FD48A, RED = 0xFFE07070;
	static final int W = 320, H = 218, LEFT_W = 92, ROW_H = 28, ROWS = 4;

	enum Tab { SHOP, QUESTS }

	/** Developer screenshots only: an entity to draw when there is no world (see {@code TradeScreenShot}). */
	static LivingEntity portraitOverride;
	private Snapshot snap;
	private Tab tab = Tab.QUESTS;
	private int scroll;
	private final List<ButtonWidget> rowButtons = new ArrayList<>();
	private ButtonWidget shopTab, questTab;
	private int x0, y0;
	private List<Text> karmaTooltip;

	public TradeScreen(Snapshot snap) {
		super(Text.translatable("screen.luminary.trade.title"));
		this.snap = snap;
		if (snap.quests.isEmpty() && !snap.offers.isEmpty()) {
			tab = Tab.SHOP;
		}
	}

	/** The server sent a new snapshot while this screen is open. */
	void update(Snapshot fresh) {
		this.snap = fresh;
		rebuild();
	}

	@Override
	protected void init() {
		x0 = (width - W) / 2;
		y0 = (height - H) / 2;
		rebuild();
	}

	private void rebuild() {
		clearChildren();
		rowButtons.clear();
		int tabX = x0 + LEFT_W + 8, tabY = y0 + 8;
		questTab = addDrawableChild(new ThemeButton(tabX, tabY, 70, 16, Text.translatable("screen.luminary.trade.tab.quests"), b -> switchTab(Tab.QUESTS), tab == Tab.QUESTS));
		shopTab = addDrawableChild(new ThemeButton(tabX + 74, tabY, 70, 16, Text.translatable("screen.luminary.trade.tab.shop"), b -> switchTab(Tab.SHOP), tab == Tab.SHOP));
		addDrawableChild(new ThemeButton(tabX + 148, tabY, 64, 16, Text.translatable("screen.luminary.trade.tab.gear"), b -> send(TradeManager.ACTION_GEAR, ""), false));
		// alms under the karma lines: emeralds for karma
		ThemeButton donate = new ThemeButton(x0 + 10, y0 + 170, LEFT_W - 20, 14, Text.translatable("screen.luminary.trade.donate", dev.luminary.core.npc.relationship.KarmaEffects.DONATION_EMERALDS),
				b -> send(TradeManager.ACTION_DONATE, ""), false);
		donate.active = client.player != null && client.player.getInventory().count(net.minecraft.item.Items.EMERALD) >= dev.luminary.core.npc.relationship.KarmaEffects.DONATION_EMERALDS;
		addDrawableChild(donate);
		// bottom row: talk and close
		addDrawableChild(new ThemeButton(x0 + 8, y0 + H - 24, LEFT_W - 16, 16, Text.translatable("screen.luminary.trade.talk"), b -> {
			send(TradeManager.ACTION_TALK, "");
			close();
		}, false));
		addDrawableChild(new ThemeButton(x0 + W - 60, y0 + H - 24, 52, 16, Text.translatable("gui.done"), b -> close(), false));
		int listY = y0 + 30;
		int rows = tab == Tab.SHOP ? snap.offers.size() : snap.quests.size();
		scroll = Math.max(0, Math.min(scroll, rows - ROWS));
		for (int i = 0; i < ROWS; i++) {
			int idx = scroll + i;
			if (idx >= rows) {
				break;
			}
			int ry = listY + i * ROW_H;
			int bx = x0 + W - 8 - 54, by = ry + 6;
			if (tab == Tab.SHOP) {
				Offer o = snap.offers.get(idx);
				boolean can = !o.owned && snap.points >= o.price;
				Text label = o.owned ? Text.translatable("screen.luminary.trade.owned") : Text.translatable("screen.luminary.trade.buy");
				ThemeButton b = new ThemeButton(bx, by, 54, 16, label, btn -> send(TradeManager.ACTION_BUY, Integer.toString(idx)), false);
				b.active = can;
				rowButtons.add(addDrawableChild(b));
			} else {
				QuestRow q = snap.quests.get(idx);
				if (q.state == 0 && q.special && !snap.worthy) {   // the blade is not for this one: no accepting
					ThemeButton b = new ThemeButton(bx - 20, by, 74, 16, Text.translatable("screen.luminary.trade.unworthy"), btn -> {
					}, false);
					b.active = false;
					rowButtons.add(addDrawableChild(b));
				} else if (q.state == 0) {        // AVAILABLE
					rowButtons.add(addDrawableChild(new ThemeButton(bx, by, 54, 16, Text.translatable("screen.luminary.trade.accept"), btn -> send(TradeManager.ACTION_ACCEPT, q.id), q.special)));
				} else if (q.state == 2) { // CLAIMABLE
					rowButtons.add(addDrawableChild(new ThemeButton(bx, by, 54, 16, Text.translatable("screen.luminary.trade.claim"), btn -> send(TradeManager.ACTION_CLAIM, q.id), true)));
				}
			}
		}
	}

	/** Developer screenshots only. */
	void devSwitch(Tab t) {
		switchTab(t);
	}

	private void switchTab(Tab t) {
		tab = t;
		scroll = 0;
		rebuild();
	}

	private void send(int action, String arg) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(snap.entityId);
		buf.writeByte(action);
		buf.writeString(arg, 256);
		ClientPlayNetworking.send(TradeManager.ACTION_PACKET, buf);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		int rows = tab == Tab.SHOP ? snap.offers.size() : snap.quests.size();
		int before = scroll;
		scroll = Math.max(0, Math.min(scroll - (int) Math.signum(amount), rows - ROWS));
		if (scroll != before) {
			rebuild();
		}
		return true;
	}

	@Override
	public void render(DrawContext c, int mouseX, int mouseY, float delta) {
		renderBackground(c);
		karmaTooltip = null;
		TextRenderer tr = textRenderer;
		// frame
		c.fill(x0 - 2, y0 - 2, x0 + W + 2, y0 + H + 2, GOLD_DIM);
		c.fill(x0, y0, x0 + W, y0 + H, BG);
		c.fillGradient(x0, y0, x0 + W, y0 + 24, 0x40E2B857, 0x00000000);
		// left column: the portrait
		c.fill(x0 + 6, y0 + 6, x0 + LEFT_W - 6, y0 + H - 30, PANEL);
		c.fill(x0 + 6, y0 + 6, x0 + LEFT_W - 6, y0 + 7, GOLD_DIM);
		Entity e = client.world == null ? portraitOverride : client.world.getEntityById(snap.entityId);
		if (e instanceof LivingEntity living) {
			int px = x0 + LEFT_W / 2, py = y0 + 98;   // feet; 34 px per block keeps the armoured body (wings, halo) inside the panel
			c.enableScissor(x0 + 7, y0 + 8, x0 + LEFT_W - 7, y0 + 108);
			try {
				InventoryScreen.drawEntity(c, px, py, 32, (float) px - mouseX, (float) (py - 52) - mouseY, living);
			} catch (RuntimeException ex) {
				portraitOverride = null;   // a world-less portrait that cannot be drawn: leave the panel empty
				dev.luminary.fabric.Luminary.LOGGER.warn("[luminary] portrait not drawn", ex);
			}
			c.disableScissor();
		}
		c.drawCenteredTextWithShadow(tr, snap.name.copy().styled(s -> s.withColor(GOLD).withBold(true)), x0 + LEFT_W / 2, y0 + 113, GOLD);
		Text pts = Text.literal("✦ " + snap.points).styled(s -> s.withColor(GOLD));
		c.drawCenteredTextWithShadow(tr, pts, x0 + LEFT_W / 2, y0 + 125, GOLD);
		c.drawCenteredTextWithShadow(tr, Text.translatable("screen.luminary.trade.points").styled(s -> s.withColor(MUTED)), x0 + LEFT_W / 2, y0 + 135, MUTED);
		// karma: the number, his attitude, and what it does to luck and prices
		int karmaColor = snap.karma < 0 ? RED : snap.karma > 0 ? GREEN : MUTED;
		String karmaText = (snap.karma > 0 ? "+" : "") + (int) Math.round(snap.karma);
		c.drawCenteredTextWithShadow(tr, Text.translatable("screen.luminary.trade.karma", karmaText).styled(s -> s.withColor(karmaColor)), x0 + LEFT_W / 2, y0 + 148, karmaColor);
		c.drawCenteredTextWithShadow(tr, Text.translatable("screen.luminary.trade.standing." + snap.standing.toLowerCase(java.util.Locale.ROOT)).styled(s -> s.withColor(MUTED)), x0 + LEFT_W / 2, y0 + 158, MUTED);
		if (mouseX >= x0 + 6 && mouseX < x0 + LEFT_W - 6 && mouseY >= y0 + 144 && mouseY < y0 + 168) {
			karmaTooltip = karmaTooltip();
		}
		// right column: the list
		int listX = x0 + LEFT_W + 8, listY = y0 + 30, listW = W - LEFT_W - 16, listH = ROWS * ROW_H;
		c.fill(listX, listY - 2, listX + listW, listY + listH + 2, PANEL);
		int rows = tab == Tab.SHOP ? snap.offers.size() : snap.quests.size();
		List<Text> tooltip = null;
		if (rows == 0) {
			c.drawCenteredTextWithShadow(tr, Text.translatable(tab == Tab.SHOP ? "screen.luminary.trade.no_offers" : "screen.luminary.trade.no_quests"), listX + listW / 2, listY + listH / 2 - 4, MUTED);
		}
		for (int i = 0; i < ROWS && scroll + i < rows; i++) {
			int idx = scroll + i, ry = listY + i * ROW_H;
			boolean hover = mouseX >= listX && mouseX < listX + listW - 60 && mouseY >= ry && mouseY < ry + ROW_H;
			if (hover) {
				c.fill(listX, ry, listX + listW, ry + ROW_H, PANEL_LIGHT);
			}
			c.fill(listX + 4, ry + ROW_H - 1, listX + listW - 4, ry + ROW_H, 0x30E2B857);
			if (tab == Tab.SHOP) {
				Offer o = snap.offers.get(idx);
				c.drawItem(o.stack, listX + 4, ry + 5);
				c.drawItemInSlot(tr, o.stack, listX + 4, ry + 5);
				Text name = o.stack.getName();
				c.drawTextWithShadow(tr, trim(tr, name, listW - 24 - 64), listX + 24, ry + 4, TEXT);
				boolean can = !o.owned && snap.points >= o.price;
				c.drawTextWithShadow(tr, Text.literal("✦ " + o.price), listX + 24, ry + 15, can ? GOLD : RED);
				if (o.unique) {
					c.drawTextWithShadow(tr, Text.translatable("screen.luminary.trade.unique"), listX + 24 + tr.getWidth("✦ " + o.price) + 6, ry + 15, 0xFFC9A0FF);
				}
				if (hover) {
					tooltip = new ArrayList<>(o.stack.getTooltip(client.player, net.minecraft.client.item.TooltipContext.Default.BASIC));
				}
			} else {
				QuestRow q = snap.quests.get(idx);
				int titleColor = q.special ? GOLD : q.state == 3 ? MUTED : TEXT;
				Text title = trim(tr, Text.translatable(q.titleKey), listW - 70);
				c.drawTextWithShadow(tr, q.special ? Text.literal("✦ ").append(title).styled(s -> s.withColor(GOLD)) : title, listX + 6, ry + 4, titleColor);
				String status = switch (q.state) {
					case 0 -> q.special ? Text.translatable("screen.luminary.trade.state.special").getString() : Text.translatable("screen.luminary.trade.state.available").getString();
					case 1 -> q.progress + "/" + q.required;
					case 2 -> Text.translatable("screen.luminary.trade.state.claimable").getString();
					case 3 -> Text.translatable("screen.luminary.trade.state.done").getString();
					default -> Text.translatable("screen.luminary.trade.state.locked").getString();
				};
				int statusColor = q.state == 2 ? GREEN : q.state == 3 ? MUTED : q.state == 4 ? GOLD_DIM : MUTED;
				c.drawTextWithShadow(tr, Text.literal(status), listX + 6, ry + 15, statusColor);
				if (q.rewardPoints > 0 && q.state != 3) {
					String reward = "+" + q.rewardPoints + " ✦";
					c.drawTextWithShadow(tr, Text.literal(reward), listX + listW - 64 - tr.getWidth(reward), ry + 15, GOLD);
				}
				if (q.state == 1 || q.state == 2) {   // a thin progress bar on its own line
					int bx = listX + 6, bw = listW - 64 - 6;
					c.fill(bx, ry + 24, bx + bw, ry + 26, 0xFF0A1020);
					c.fill(bx, ry + 24, bx + (int) (bw * Math.min(1.0, q.progress / (double) Math.max(1, q.required))), ry + 26, q.state == 2 ? GREEN : GOLD);
				}
				if (hover) {
					tooltip = questTooltip(q);
				}
			}
		}
		if (rows > ROWS) {   // scroll marks
			c.drawTextWithShadow(tr, Text.literal(scroll > 0 ? "▲" : " "), listX + listW - 9, listY - 1, GOLD_DIM);
			c.drawTextWithShadow(tr, Text.literal(scroll + ROWS < rows ? "▼" : " "), listX + listW - 9, listY + listH - 8, GOLD_DIM);
		}
		// his line at the bottom
		int boxX = x0 + LEFT_W + 8, boxY = y0 + 30 + listH + 6, boxW = listW, boxH = H - (boxY - y0) - 30;
		c.fill(boxX, boxY, boxX + boxW, boxY + boxH, PANEL);
		c.fill(boxX, boxY, boxX + 2, boxY + boxH, GOLD);
		String said = snap.message.startsWith("!") ? Text.translatable(snap.message.substring(1)).getString() : snap.greeting;
		int color = snap.message.startsWith("!") ? RED : TEXT;
		int ty = boxY + 4;
		for (OrderedText line : tr.wrapLines(Text.literal(said), boxW - 10)) {
			if (ty + 9 > boxY + boxH) {
				break;
			}
			c.drawText(tr, line, boxX + 6, ty, color, false);
			ty += 10;
		}
		super.render(c, mouseX, mouseY, delta);
		if (tooltip != null) {
			c.drawTooltip(tr, tooltip, mouseX, mouseY);
		} else if (karmaTooltip != null) {
			c.drawTooltip(tr, karmaTooltip, mouseX, mouseY);
		}
	}

	private List<Text> karmaTooltip() {
		List<Text> out = new ArrayList<>();
		out.add(Text.translatable("screen.luminary.trade.karma.title").formatted(Formatting.GOLD, Formatting.BOLD));
		out.add(Text.translatable("screen.luminary.trade.karma.trust", (int) Math.round(snap.trust), (int) Math.round(snap.threat)).formatted(Formatting.GRAY));
		String luck = (snap.luck > 0 ? "+" : "") + (snap.luck == Math.floor(snap.luck) ? Integer.toString((int) snap.luck) : Double.toString(snap.luck));
		out.add(Text.translatable("screen.luminary.trade.karma.luck", luck).formatted(snap.luck < 0 ? Formatting.RED : snap.luck > 0 ? Formatting.GREEN : Formatting.GRAY));
		out.add(Text.translatable("screen.luminary.trade.karma.prices", (int) Math.round(snap.markup * 100)).formatted(snap.markup > 0 ? Formatting.RED : Formatting.GRAY));
		out.add(Text.empty());
		out.add(Text.translatable(snap.worthy ? "screen.luminary.trade.karma.worthy" : "screen.luminary.trade.karma.unworthy").formatted(snap.worthy ? Formatting.GREEN : Formatting.RED));
		out.add(Text.translatable("screen.luminary.trade.karma.how").formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
		out.add(Text.translatable("screen.luminary.trade.karma.alms", dev.luminary.core.npc.relationship.KarmaEffects.DONATION_EMERALDS, dev.luminary.core.npc.relationship.KarmaEffects.DONATION_KARMA).formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
		return out;
	}

	private List<Text> questTooltip(QuestRow q) {
		List<Text> out = new ArrayList<>();
		out.add(Text.translatable(q.titleKey).formatted(q.special ? Formatting.GOLD : Formatting.WHITE, Formatting.BOLD));
		for (OrderedText line : textRenderer.wrapLines(Text.translatable(q.descKey).formatted(Formatting.GRAY), 220)) {
			out.add(Text.literal(orderedToString(line)).formatted(Formatting.GRAY));
		}
		out.add(Text.empty());
		for (Kill k : q.kills) {
			Text name = EntityType.get(k.entity).<Text>map(EntityType::getName).orElse(Text.literal(k.entity));
			out.add(Text.literal("• ").append(name).append(Text.literal("  " + k.done + "/" + k.required)).formatted(k.done >= k.required ? Formatting.GREEN : Formatting.YELLOW));
		}
		out.add(Text.empty());
		if (q.special) {
			out.add(Text.translatable("screen.luminary.trade.reward.sword").formatted(Formatting.GOLD));
		} else {
			out.add(Text.translatable("screen.luminary.trade.reward.points", q.rewardPoints).formatted(Formatting.GOLD));
		}
		if (q.resetOnDeath) {
			out.add(Text.translatable("screen.luminary.trade.reset_on_death").formatted(Formatting.RED));
		}
		if (q.state == 4) {
			out.add(Text.translatable("screen.luminary.trade.locked_hint", dev.luminary.core.npc.trade.TradeRules.SPECIAL_AFTER_QUESTS).formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
		}
		if (q.special && !snap.worthy) {
			out.add(Text.translatable("luminary.trade.unworthy").formatted(Formatting.RED));
		}
		return out;
	}

	private static String orderedToString(OrderedText t) {
		StringBuilder sb = new StringBuilder();
		t.accept((i, style, cp) -> {
			sb.appendCodePoint(cp);
			return true;
		});
		return sb.toString();
	}

	private static Text trim(TextRenderer tr, Text text, int width) {
		if (tr.getWidth(text) <= width) {
			return text;
		}
		String s = text.getString();
		while (!s.isEmpty() && tr.getWidth(s + "…") > width) {
			s = s.substring(0, s.length() - 1);
		}
		return Text.literal(s + "…").setStyle(text.getStyle());
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ------------------------------------------------------------------ data from the server

	record Offer(ItemStack stack, int price, boolean unique, boolean owned) {
	}

	record Kill(String entity, int required, int done) {
	}

	record QuestRow(String id, String titleKey, String descKey, int state, int progress, int required, int rewardPoints, boolean special, boolean resetOnDeath, List<Kill> kills) {
	}

	/** One snapshot of the trade packet; read on the network thread, used on the render thread. */
	public record Snapshot(int entityId, Text name, String greeting, String message, int points, int completed, int purchases, boolean specialAvailable,
						   double karma, double trust, double threat, String standing, boolean worthy, double luck, double markup,
						   List<Offer> offers, List<QuestRow> quests) {
		static Snapshot read(PacketByteBuf buf) {
			int entityId = buf.readVarInt();
			Text name = buf.readText();
			String greeting = buf.readString(4096);
			String message = buf.readString(4096);
			int points = buf.readVarInt(), completed = buf.readVarInt(), purchases = buf.readVarInt();
			boolean special = buf.readBoolean();
			double karma = buf.readDouble(), trust = buf.readDouble(), threat = buf.readDouble();
			String standing = buf.readString(32);
			boolean worthy = buf.readBoolean();
			double luck = buf.readDouble(), markup = buf.readDouble();
			int n = buf.readVarInt();
			List<Offer> offers = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				offers.add(new Offer(buf.readItemStack(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));
			}
			n = buf.readVarInt();
			List<QuestRow> quests = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				String id = buf.readString(256), title = buf.readString(256), desc = buf.readString(256);
				int state = buf.readByte(), progress = buf.readVarInt(), required = buf.readVarInt(), reward = buf.readVarInt();
				boolean sp = buf.readBoolean(), reset = buf.readBoolean();
				int kn = buf.readVarInt();
				List<Kill> kills = new ArrayList<>();
				for (int k = 0; k < kn; k++) {
					kills.add(new Kill(buf.readString(256), buf.readVarInt(), buf.readVarInt()));
				}
				quests.add(new QuestRow(id, title, desc, state, progress, required, reward, sp, reset, kills));
			}
			return new Snapshot(entityId, name, greeting, message, points, completed, purchases, special, karma, trust, threat, standing, worthy, luck, markup, offers, quests);
		}
	}

	/** Client side: the server's snapshot opens the screen or refreshes the one already open. */
	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(TradeManager.OPEN_PACKET, (client, handler, buf, sender) -> {
			Snapshot snap = Snapshot.read(buf);
			client.execute(() -> {
				if (client.currentScreen instanceof TradeScreen open && open.snap.entityId == snap.entityId) {
					open.update(snap);
				} else {
					client.setScreen(new TradeScreen(snap));
				}
			});
		});
	}

	/** A flat button in the screen's colours: navy field, gold edge, gold text when it is the active tab or the thing to do now. */
	@Environment(EnvType.CLIENT)
	public static final class ThemeButton extends ButtonWidget {
		private final boolean highlighted;

		public ThemeButton(int x, int y, int w, int h, Text label, PressAction action, boolean highlighted) {
			super(x, y, w, h, label, action, DEFAULT_NARRATION_SUPPLIER);
			this.highlighted = highlighted;
		}

		@Override
		public void renderButton(DrawContext c, int mouseX, int mouseY, float delta) {
			int edge = !active ? 0xFF3A4660 : highlighted || isHovered() ? GOLD : GOLD_DIM;
			int fill = !active ? 0xFF111A2C : isHovered() ? PANEL_LIGHT : highlighted ? 0xFF24365C : PANEL;
			c.fill(getX(), getY(), getX() + width, getY() + height, edge);
			c.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, fill);
			int color = !active ? 0xFF6B7690 : highlighted || isHovered() ? GOLD : TEXT;
			TextRenderer tr = MinecraftClient.getInstance().textRenderer;
			c.drawCenteredTextWithShadow(tr, getMessage(), getX() + width / 2, getY() + (height - 8) / 2, color);
		}
	}
}
