package luminary.vsfix.mixin;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the fix only to the Valkyrien Skies build it was written against: the mixin targets Kotlin lambda
 * names (register$lambda$1, registerClientNetworking$lambda$0) that another VS build may not have.
 */
public final class VsFixMixinPlugin implements IMixinConfigPlugin {
    static final String SUPPORTED_VS = "2.4.11";
    private static final Logger LOG = LoggerFactory.getLogger("luminary-vsfix");
    private boolean apply;

    @Override
    public void onLoad(String mixinPackage) {
        Optional<ModContainer> vs = FabricLoader.getInstance().getModContainer("valkyrienskies");
        String version = vs.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
        apply = SUPPORTED_VS.equals(version);
        if (apply) {
            LOG.info("Valkyrien Skies {}: its network packets will be handled on the game thread", version);
        } else if (version != null) {
            LOG.warn("Valkyrien Skies {} is not {}: fix not applied, check whether this VS build still needs it",
                    version, SUPPORTED_VS);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return apply;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
