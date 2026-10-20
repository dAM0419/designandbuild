package dev.abcd;

import dev.abcd.client.ClientSetup;
import dev.abcd.net.Net;
import dev.abcd.server.ServerEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Awesome Building Chunk Design (ABCD).
 * 도면 테이블에서 주변 청크를 쿼터뷰로 설계하고, 도면을 들고 월드에서 단계별로 따라 짓는다.
 */
@Mod(ABCD.MODID)
public final class ABCD {
    public static final String MODID = "designandbuild";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public ABCD(IEventBus modBus, ModContainer container) {
        Registration.register(modBus);
        modBus.addListener(Net::register);
        modBus.addListener(ABCD::buildCreativeTabs);
        ServerEvents.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(Registration.TABLE_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(Registration.BLUEPRINT.get());
            event.accept(Registration.HAMMER.get());
        }
    }
}
