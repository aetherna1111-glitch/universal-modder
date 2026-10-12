package dev.luminary.fabric.client;

import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;

/**
 * Client entry point: gives the NPC a renderer so a client with the mod can see it. Placeholder look (default
 * player model and skin); a GeckoLib model is a later slice. The server never loads this class.
 */
@Environment(EnvType.CLIENT)
public final class LuminaryClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(NpcEntities.NPC, NpcRenderer::new);
	}

	@Environment(EnvType.CLIENT)
	static final class NpcRenderer extends BipedEntityRenderer<LuminaryNpcEntity, BipedEntityModel<LuminaryNpcEntity>> {
		NpcRenderer(EntityRendererFactory.Context ctx) {
			super(ctx, new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER)), 0.5f);
		}

		@Override
		public Identifier getTexture(LuminaryNpcEntity entity) {
			return DefaultSkinHelper.getTexture();
		}
	}
}
