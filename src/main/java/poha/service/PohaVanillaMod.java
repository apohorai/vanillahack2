package poha.service;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Lives in src/main/java — only reference classes that are
 * safe on both client and server here. All client-only code (KeyMapping,
 * Minecraft, InputConstants, ClientTickEvents...) belongs in
 * src/client/java/poha/service/PohaVanillaModClient.java instead.
 */
public class PohaVanillaMod implements ModInitializer {
	public static final String MOD_ID = "pohavanillamod";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}