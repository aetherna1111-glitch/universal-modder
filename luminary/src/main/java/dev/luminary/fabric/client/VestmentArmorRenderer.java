package dev.luminary.fabric.client;

import dev.luminary.fabric.npc.LuminArmorItem;
import dev.luminary.fabric.npc.LuminaryItems;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.EnumMap;
import java.util.Map;

/**
 * The volumetric Vestments of the Envoy. One biped-shaped model per slot, with no base cubes, only the vestment's
 * own cuboids attached to the vanilla part names (head, body, arms, legs) so they follow every pose; the renderer
 * copies the wearer's pose and draws the slot's model over the player. Generated together with the atlas texture
 * from one spec, so UVs and cuboids always agree.
 */
@Environment(EnvType.CLIENT)
public final class VestmentArmorRenderer implements ArmorRenderer {
	static final Identifier ATLAS = new Identifier("luminary", "textures/models/armor/vestments_atlas.png");
	private final Map<EquipmentSlot, BipedEntityModel<LivingEntity>> models = new EnumMap<>(EquipmentSlot.class);

	public VestmentArmorRenderer() {
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			models.put(slot, new BipedEntityModel<>(data(slot).createModel()));
		}
	}

	public static void register() {
		ArmorRenderer.register(new VestmentArmorRenderer(), LuminaryItems.VESTMENT_HELMET, LuminaryItems.VESTMENT_CHESTPLATE, LuminaryItems.VESTMENT_LEGGINGS, LuminaryItems.VESTMENT_BOOTS);
	}

	@Override
	public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, ItemStack stack, LivingEntity entity, EquipmentSlot slot, int light, BipedEntityModel<LivingEntity> contextModel) {
		if (!(stack.getItem() instanceof LuminArmorItem armor) || armor.getSlotType() != slot) {
			return;
		}
		BipedEntityModel<LivingEntity> model = models.get(slot);
		if (model == null) {
			return;
		}
		contextModel.copyBipedStateTo(model);
		model.setVisible(true);
		ArmorRenderer.renderPart(matrices, vertexConsumers, light, stack, model, ATLAS);
	}

	/** An empty biped skeleton: the vanilla part names and pivots, no cubes of their own. */
	private static ModelData skeleton() {
		ModelData data = new ModelData();
		ModelPartData root = data.getRoot();
		root.addChild("head", ModelPartBuilder.create(), ModelTransform.NONE);
		root.addChild("hat", ModelPartBuilder.create(), ModelTransform.NONE);
		root.addChild("body", ModelPartBuilder.create(), ModelTransform.NONE);
		root.addChild("right_arm", ModelPartBuilder.create(), ModelTransform.pivot(-5.0f, 2.0f, 0.0f));
		root.addChild("left_arm", ModelPartBuilder.create(), ModelTransform.pivot(5.0f, 2.0f, 0.0f));
		root.addChild("right_leg", ModelPartBuilder.create(), ModelTransform.pivot(-1.9f, 12.0f, 0.0f));
		root.addChild("left_leg", ModelPartBuilder.create(), ModelTransform.pivot(1.9f, 12.0f, 0.0f));
		return data;
	}

	private static ModelPartData part(ModelPartData root, String name) {
		return root.getChild(name);
	}

	static TexturedModelData data(EquipmentSlot slot) {
		ModelData data = skeleton();
		ModelPartData root = data.getRoot();
		switch (slot) {
			case HEAD -> {
				part(root, "head").addChild("crown", ModelPartBuilder.create().uv(0, 0).cuboid(-4.6f, -8.6f, -4.6f, 9.2f, 3.0f, 9.2f), ModelTransform.NONE);
				part(root, "head").addChild("side_l", ModelPartBuilder.create().uv(37, 0).cuboid(3.6f, -5.6f, -4.6f, 1.0f, 5.0f, 9.2f), ModelTransform.NONE);
				part(root, "head").addChild("side_r", ModelPartBuilder.create().uv(58, 0).cuboid(-4.6f, -5.6f, -4.6f, 1.0f, 5.0f, 9.2f), ModelTransform.NONE);
				part(root, "head").addChild("back", ModelPartBuilder.create().uv(79, 0).cuboid(-4.6f, -5.6f, 3.6f, 9.2f, 5.0f, 1.0f), ModelTransform.NONE);
				part(root, "head").addChild("brow", ModelPartBuilder.create().uv(100, 0).cuboid(-4.6f, -5.8f, -4.7f, 9.2f, 1.2f, 1.1f), ModelTransform.NONE);
				part(root, "head").addChild("cheek_l", ModelPartBuilder.create().uv(121, 0).cuboid(3.3f, -4.6f, -4.7f, 1.3f, 3.5f, 1.1f), ModelTransform.NONE);
				part(root, "head").addChild("cheek_r", ModelPartBuilder.create().uv(0, 15).cuboid(-4.6f, -4.6f, -4.7f, 1.3f, 3.5f, 1.1f), ModelTransform.NONE);
				part(root, "head").addChild("gem", ModelPartBuilder.create().uv(5, 15).cuboid(-1.0f, -6.4f, -5.2f, 2.0f, 1.8f, 0.6f), ModelTransform.NONE);
				part(root, "head").addChild("crest", ModelPartBuilder.create().uv(11, 15).cuboid(-0.5f, -12.2f, -2.5f, 1.0f, 3.8f, 7.5f), ModelTransform.NONE);
				part(root, "head").addChild("crest_tip", ModelPartBuilder.create().uv(28, 15).cuboid(-0.5f, -13.4f, -0.5f, 1.0f, 1.4f, 3.0f), ModelTransform.NONE);
				part(root, "head").addChild("halo_f", ModelPartBuilder.create().uv(36, 15).cuboid(-5.5f, -11.0f, -5.5f, 11f, 0.5f, 1f), ModelTransform.NONE);
				part(root, "head").addChild("halo_b", ModelPartBuilder.create().uv(60, 15).cuboid(-5.5f, -11.0f, 4.5f, 11f, 0.5f, 1f), ModelTransform.NONE);
				part(root, "head").addChild("halo_l", ModelPartBuilder.create().uv(84, 15).cuboid(4.5f, -11.0f, -4.5f, 1f, 0.5f, 9f), ModelTransform.NONE);
				part(root, "head").addChild("halo_r", ModelPartBuilder.create().uv(104, 15).cuboid(-5.5f, -11.0f, -4.5f, 1f, 0.5f, 9f), ModelTransform.NONE);
			}
			case CHEST -> {
				part(root, "body").addChild("breast", ModelPartBuilder.create().uv(0, 27).cuboid(-4.6f, 0.2f, -2.7f, 9.2f, 6.5f, 1.2f), ModelTransform.NONE);
				part(root, "body").addChild("breast_lower", ModelPartBuilder.create().uv(21, 27).cuboid(-4.6f, 6.9f, -2.6f, 9.2f, 4.3f, 1.0f), ModelTransform.NONE);
				part(root, "body").addChild("back", ModelPartBuilder.create().uv(42, 27).cuboid(-4.6f, 0.2f, 1.6f, 9.2f, 10.8f, 1.1f), ModelTransform.NONE);
				part(root, "body").addChild("side_l", ModelPartBuilder.create().uv(63, 27).cuboid(3.6f, 0.2f, -2.6f, 1.0f, 10.8f, 5.2f), ModelTransform.NONE);
				part(root, "body").addChild("side_r", ModelPartBuilder.create().uv(76, 27).cuboid(-4.6f, 0.2f, -2.6f, 1.0f, 10.8f, 5.2f), ModelTransform.NONE);
				part(root, "body").addChild("collar", ModelPartBuilder.create().uv(89, 27).cuboid(-4.8f, -0.6f, -2.8f, 9.6f, 1.0f, 5.6f), ModelTransform.NONE);
				part(root, "body").addChild("trim_mid", ModelPartBuilder.create().uv(0, 43).cuboid(-4.6f, 6.4f, -2.9f, 9.2f, 0.8f, 0.6f), ModelTransform.NONE);
				part(root, "body").addChild("gem", ModelPartBuilder.create().uv(20, 43).cuboid(-1.2f, 2.0f, -3.4f, 2.4f, 2.4f, 0.8f), ModelTransform.NONE);
				part(root, "body").addChild("gem_ring", ModelPartBuilder.create().uv(27, 43).cuboid(-1.8f, 1.4f, -3.1f, 3.6f, 3.6f, 0.3f), ModelTransform.NONE);
				part(root, "body").addChild("rune_l", ModelPartBuilder.create().uv(35, 43).cuboid(-3.6f, 3.0f, -3.0f, 1.0f, 2.0f, 0.3f), ModelTransform.NONE);
				part(root, "body").addChild("rune_r", ModelPartBuilder.create().uv(38, 43).cuboid(2.6f, 3.0f, -3.0f, 1.0f, 2.0f, 0.3f), ModelTransform.NONE);
				part(root, "left_arm").addChild("pauldron_l", ModelPartBuilder.create().uv(41, 43).cuboid(-1.6f, -2.6f, -2.6f, 5.2f, 4.2f, 5.2f), ModelTransform.NONE);
				part(root, "left_arm").addChild("pauldron_trim_l", ModelPartBuilder.create().uv(62, 43).cuboid(-1.7000000000000002f, 1.5f, -2.7f, 5.4f, 0.8f, 5.4f), ModelTransform.NONE);
				part(root, "left_arm").addChild("fin_l", ModelPartBuilder.create().uv(84, 43).cuboid(3.9f, -6.2f, -1.2f, 1.0f, 5.0f, 2.4f), ModelTransform.NONE);
				part(root, "left_arm").addChild("fin_tip_l", ModelPartBuilder.create().uv(91, 43).cuboid(3.9f, -7.4f, -0.4f, 1.0f, 1.2f, 0.8f), ModelTransform.NONE);
				part(root, "right_arm").addChild("pauldron_r", ModelPartBuilder.create().uv(95, 43).cuboid(-3.6f, -2.6f, -2.6f, 5.2f, 4.2f, 5.2f), ModelTransform.NONE);
				part(root, "right_arm").addChild("pauldron_trim_r", ModelPartBuilder.create().uv(0, 53).cuboid(-3.7f, 1.5f, -2.7f, 5.4f, 0.8f, 5.4f), ModelTransform.NONE);
				part(root, "right_arm").addChild("fin_r", ModelPartBuilder.create().uv(22, 53).cuboid(-4.9f, -6.2f, -1.2f, 1.0f, 5.0f, 2.4f), ModelTransform.NONE);
				part(root, "right_arm").addChild("fin_tip_r", ModelPartBuilder.create().uv(29, 53).cuboid(-4.9f, -7.4f, -0.4f, 1.0f, 1.2f, 0.8f), ModelTransform.NONE);
				part(root, "body").addChild("wing_l", ModelPartBuilder.create().uv(33, 53).cuboid(0f, -12.0f, 0.0f, 7.0f, 13.0f, 0.6f), ModelTransform.of(3.4f, 1.0f, 2.9f, 0.1f, -0.3f, 0.7f));
				part(root, "body").addChild("wing_l_vane", ModelPartBuilder.create().uv(49, 53).cuboid(2.2f, -10.0f, 0.15f, 3.2f, 9.5f, 0.3f), ModelTransform.of(3.4f, 1.0f, 2.9f, 0.1f, -0.3f, 0.7f));
				part(root, "body").addChild("wing_l_tip", ModelPartBuilder.create().uv(56, 53).cuboid(6.6f, -14.5f, 0.1f, 2.6f, 6.0f, 0.4f), ModelTransform.of(3.4f, 1.0f, 2.9f, 0.1f, -0.3f, 0.7f));
				part(root, "body").addChild("wing_r", ModelPartBuilder.create().uv(62, 53).cuboid(-7.0f, -12.0f, 0.0f, 7.0f, 13.0f, 0.6f), ModelTransform.of(-3.4f, 1.0f, 2.9f, 0.1f, 0.3f, -0.7f));
				part(root, "body").addChild("wing_r_vane", ModelPartBuilder.create().uv(78, 53).cuboid(-5.4f, -10.0f, 0.15f, 3.2f, 9.5f, 0.3f), ModelTransform.of(-3.4f, 1.0f, 2.9f, 0.1f, 0.3f, -0.7f));
				part(root, "body").addChild("wing_r_tip", ModelPartBuilder.create().uv(85, 53).cuboid(-9.2f, -14.5f, 0.1f, 2.6f, 6.0f, 0.4f), ModelTransform.of(-3.4f, 1.0f, 2.9f, 0.1f, 0.3f, -0.7f));
			}
			case LEGS -> {
				part(root, "body").addChild("belt", ModelPartBuilder.create().uv(91, 53).cuboid(-4.7f, 10.6f, -2.7f, 9.4f, 1.6f, 5.4f), ModelTransform.NONE);
				part(root, "body").addChild("belt_gem", ModelPartBuilder.create().uv(121, 53).cuboid(-0.9f, 10.4f, -3.1f, 1.8f, 2.0f, 0.5f), ModelTransform.NONE);
				part(root, "body").addChild("tasset_f", ModelPartBuilder.create().uv(0, 67).cuboid(-4.4f, 12.0f, -2.8f, 8.8f, 3.2f, 0.8f), ModelTransform.NONE);
				part(root, "body").addChild("tasset_b", ModelPartBuilder.create().uv(20, 67).cuboid(-4.4f, 12.0f, 2.0f, 8.8f, 3.2f, 0.8f), ModelTransform.NONE);
				part(root, "left_leg").addChild("thigh_l", ModelPartBuilder.create().uv(40, 67).cuboid(-2.3f, 0.0f, -2.3f, 4.6f, 6.5f, 4.6f), ModelTransform.NONE);
				part(root, "left_leg").addChild("thigh_trim_l", ModelPartBuilder.create().uv(59, 67).cuboid(-2.4f, 5.9f, -2.4f, 4.8f, 0.8f, 4.8f), ModelTransform.NONE);
				part(root, "left_leg").addChild("knee_l", ModelPartBuilder.create().uv(79, 67).cuboid(-0.6f, 6.2f, -2.8f, 1.2f, 1.4f, 0.6f), ModelTransform.NONE);
				part(root, "right_leg").addChild("thigh_r", ModelPartBuilder.create().uv(83, 67).cuboid(-2.3f, 0.0f, -2.3f, 4.6f, 6.5f, 4.6f), ModelTransform.NONE);
				part(root, "right_leg").addChild("thigh_trim_r", ModelPartBuilder.create().uv(102, 67).cuboid(-2.4f, 5.9f, -2.4f, 4.8f, 0.8f, 4.8f), ModelTransform.NONE);
				part(root, "right_leg").addChild("knee_r", ModelPartBuilder.create().uv(122, 67).cuboid(-0.6f, 6.2f, -2.8f, 1.2f, 1.4f, 0.6f), ModelTransform.NONE);
			}
			case FEET -> {
				part(root, "left_leg").addChild("shin_l", ModelPartBuilder.create().uv(0, 79).cuboid(-2.4f, 7.8f, -2.4f, 4.8f, 4.4f, 4.8f), ModelTransform.NONE);
				part(root, "left_leg").addChild("strap_l", ModelPartBuilder.create().uv(20, 79).cuboid(-2.5f, 9.6f, -2.5f, 5.0f, 0.7f, 5.0f), ModelTransform.NONE);
				part(root, "left_leg").addChild("sole_l", ModelPartBuilder.create().uv(40, 79).cuboid(-2.5f, 11.4f, -2.5f, 5.0f, 0.8f, 5.0f), ModelTransform.NONE);
				part(root, "left_leg").addChild("wing_l", ModelPartBuilder.create().uv(60, 79).cuboid(2.4f, 7.0f, -0.6f, 0.8f, 3.0f, 1.2f), ModelTransform.NONE);
				part(root, "left_leg").addChild("wing_tip_l", ModelPartBuilder.create().uv(64, 79).cuboid(2.4f, 5.8f, -0.2f, 0.8f, 1.2f, 0.6f), ModelTransform.NONE);
				part(root, "right_leg").addChild("shin_r", ModelPartBuilder.create().uv(67, 79).cuboid(-2.4f, 7.8f, -2.4f, 4.8f, 4.4f, 4.8f), ModelTransform.NONE);
				part(root, "right_leg").addChild("strap_r", ModelPartBuilder.create().uv(87, 79).cuboid(-2.5f, 9.6f, -2.5f, 5.0f, 0.7f, 5.0f), ModelTransform.NONE);
				part(root, "right_leg").addChild("sole_r", ModelPartBuilder.create().uv(107, 79).cuboid(-2.5f, 11.4f, -2.5f, 5.0f, 0.8f, 5.0f), ModelTransform.NONE);
				part(root, "right_leg").addChild("wing_r", ModelPartBuilder.create().uv(0, 89).cuboid(-3.2f, 7.0f, -0.6f, 0.8f, 3.0f, 1.2f), ModelTransform.NONE);
				part(root, "right_leg").addChild("wing_tip_r", ModelPartBuilder.create().uv(4, 89).cuboid(-3.2f, 5.8f, -0.2f, 0.8f, 1.2f, 0.6f), ModelTransform.NONE);
			}
			default -> {
			}
		}
		return TexturedModelData.of(data, 128, 128);
	}
}
