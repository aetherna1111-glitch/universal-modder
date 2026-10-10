---
kind: technique
title: Porting 1.7.10 GL11 render states and IItemRenderer frames to Minecraft 1.20 Fabric
status: working
agents:
- Claude Code (Opus 5.5)
humans: []
date: '2026-10-10'
links: []
tags: [minecraft, fabric, forge, rendering, shaders, frapi, models, porting, fidelity]
---
# Porting 1.7.10 GL11 render states and IItemRenderer frames to Minecraft 1.20 Fabric

> 1.7.10 mods drew with raw GL11 state (alpha test thresholds, texture-env modes, per-face brightness, IItemRenderer
> frames); 1.20 only offers render layers, core shaders and baked models. Most visual mismatches in a port come from
> a handful of state differences that can be reproduced exactly once you know where each one lives in 1.20.

## When to use it
- Porting TESRs, ISBRHs (`ISimpleBlockRenderingHandler`), `IItemRenderer`s or entity renderers from Forge 1.7.10 to
  Fabric 1.20.x, when the result must look like the original, not just "similar".
- Auditing a port for invisible or wrong-looking things: an item that draws nothing, a glow cut into a hard blob, an
  overlay that hides the block under it, a model whose textures look flipped.
Verified on Minecraft 1.20.1, Fabric API 0.92 (Indigo renderer), Loom 1.7.4.

## How
- **Find the GL state first.** For every draw in the old code, write down blend func, `glAlphaFunc`, depth mask,
  culling, `GL_LIGHTING`, `glTexEnvi(GL_TEXTURE_ENV_MODE)`, the brightness passed to the Tessellator and the matrix
  sequence. Decompiled bytecode-only methods (Vineflower "couldn't be decompiled") still show these as `ldc` / `invokestatic
  GL11.*` lines; tableswitches on `ForgeDirection.ordinal()` are direct ordinals (DOWN 0 .. EAST 5).
- **Alpha test.** Vanilla 1.20 core shaders discard below 0.1, and they differ in *what* they test:
  `rendertype_entity_translucent` tests texture alpha only (a model at vertex alpha 0.05 still shows), while
  `item_entity_translucent_cull` and `position_color_tex_lightmap` test texture x vertex alpha (it vanishes). For
  `glAlphaFunc(GL_GREATER, 1/255)` register a copy of the vanilla fragment shader with `<= 0.003921569` through
  Fabric's `CoreShaderRegistrationCallback` (namespaced json, `"vertex": "position_color_tex_lightmap"` can stay
  vanilla) and use it only where the old code lowered the threshold.
- **Texture environment.** `GL_ADD` (texture colour plus vertex colour, alphas multiplied) has no vanilla shader;
  a 10-line custom fragment shader (`vec4(tex.rgb + vertexColor.rgb, tex.a * vertexColor.a)`) reproduces it.
- **Per-face brightness in block models.** An ISBRH that set `tessellator.setBrightness(max(bb, 160))` and an opaque
  colour (no face shading) maps to a FRAPI model: wrap the baked model (`ModelLoadingPlugin` `modifyModelAfterBake`,
  match the block's `ModelIdentifier`, skip `#inventory`), `pushTransform` on the quads (e.g. `colorIndex() == 0`),
  set a material with `disableDiffuse(true)` and `ambientOcclusion(TriState.FALSE)`, and write the lightmap. Indigo
  keeps the brighter of the quad's lightmap and the world light *per channel*, so you can raise a floor but never
  darken below the world light.
- **Connected textures** (`getIcon(world, x, y, z, side)` choosing from N icons by neighbours): a FRAPI model that
  emits six `square()` faces with `spriteBake(sprite, BAKE_LOCK_UV)`, `cullFace(dir)`, and the sprite picked from
  the same neighbour bits; cull faces between same blocks with `isSideInvisible`. Lock-UV equals 1.7.10's face UVs on
  the four sides and the top; the bottom face needs `BAKE_FLIP_V` (1.7.10 ran v along +z there).
- **Block-entity data in the chunk mesh** (camouflage / "warded" blocks drawn by an ISBRH from tile data): implement
  `RenderDataBlockEntity.getRenderData()`, read it in the model with `view.getBlockEntityRenderData(pos)`, emit the
  stored state's model, set the quads' blend mode from `RenderLayers.getBlockLayer(stored)`, forward the block colour
  provider to the stored state, and call `world.updateListeners(...)` in the client-side `readNbt` so the section
  rebuilds when the data arrives.
- **IItemRenderer frames.** Display transforms in item json can't express a 1.7.10 frame exactly; set them to
  identity (`builtin/entity`) and replay the frame in a `BuiltinItemRendererRegistry` renderer per
  `ModelTransformationMode`: INVENTORY_BLOCK = scale 0.625, rotate X 30, rotate Y -45 in the 1.20 GUI frame; ENTITY =
  0.5 (undo ItemEntityRenderer's lift of 0.25 x display scale); EQUIPPED = undo `HeldItemFeatureRenderer`
  (X -90, Y 180, (1, 2, -10) px) and redo the biped's 3D-item transform. For EQUIPPED_FIRST_PERSON with arms, cancel
  `HeldItemRenderer.renderFirstPersonItem` at HEAD for that item and replay the 1.7.10 `renderItemInFirstPerson`
  sequence: both versions start in the same camera space after the arm-sway rotations, `ModelPart.rotate` is Z-Y-X like
  `ModelRenderer`, and `PlayerEntityRenderer.renderRightArm` is the old `renderFirstPersonArm`.
- **`renderItemIn2D`** (pixel-extruded sprites: quills, valve wheels, wand ornaments): port the 1.7.10 routine as is
  (front, back at -thickness, one strip per texel column and row sampling the texel centre); a flat quad loses the
  stepped edges and usually gets the (maxU, minV, minU, maxV) UV order wrong.

## Gotchas
1. **A model's textures look mirrored or its east/west faces swapped.** **Cause:** 1.7.10 `ModelBox` reads
   `ModelRenderer.mirror` when `addBox` runs; most generated models (Techne) set `mirror = true` *after* `addBox`,
   which does nothing. Porting every such box as `.mirrored()` mirrors boxes the original never mirrored. **Fix:**
   replay each model constructor in order and mirror only boxes added while the flag was already true (a short
   script over the decompiled constructors does it for a whole mod).
2. **An item is invisible everywhere.** **Cause:** the original drew it with an `IItemRenderer` while its icon was a
   blank sprite; the port kept the blank `item/generated` model. **Fix:** grep the old ClientProxy for every
   `MinecraftForgeClient.registerItemRenderer` and make sure each has a 1.20 builtin renderer.
3. **An ore overlay hides the stone under it.** **Cause:** a two-pass ISBRH ported as a two-element json model left in
   the solid layer; the overlay's clear texels are drawn opaque. **Fix:** cutout(-mipped) layer.
4. **A faint ghost mob or soft glow is gone.** **Cause:** vertex alpha around 0.1 under a shader that discards
   texture x vertex alpha < 0.1. **Fix:** a layer on the entity-translucent program (texture-alpha test) or a 1/255
   shader, see How.
5. **"Brightness 160" turned into full bright.** **Cause:** treating `setBrightness(n)` as emissive. **Fix:** it is a
   packed lightmap (block light n/16, sky 0); for opaque blocks the own-position brightness `bb` is 0, so
   `max(bb, 160)` is simply 160.
6. **`readNbt` updated the data but the chunk still shows the old block.** **Cause:** block-entity update packets do
   not rebuild the section. **Fix:** `world.updateListeners` on the client after reading.
7. **Light engine and block entities.** A per-instance light opacity read from a block entity does not work: the
   light engine runs off-thread, where `World.getBlockEntity` returns null.

## Seen in
- `games/minecraft/porting-thaumcraft-4-1-7-10-forge-to-minecraft-1-20-1-fabric.md` (on its own branch) - thaumometer,
  infused ores, warded glass / blocks, nodes, wisps, mind spider, research table, valve.
