package dev.luminary.fabric.npc;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.util.List;

/**
 * Lightstone dust: the ground-down light Lumin keeps for his own work. A material sold only by him; it glints and
 * says what it is. Held in the main or off hand it lights nothing (no dynamic lights in the pack), so its use is the
 * crafts other mods accept it in and the tag {@code luminary:holy_dust}.
 */
public final class LightstoneDustItem extends Item {
	public LightstoneDustItem(Settings settings) {
		super(settings);
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.translatable("item.luminary.lightstone_dust.lore").formatted(Formatting.GRAY, Formatting.ITALIC));
		tooltip.add(Text.translatable("item.luminary.sold_by_lumin").formatted(Formatting.GOLD));
	}
}
