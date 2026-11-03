package com.capy.client.gltf;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.CustomKnifeModule;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Drops the parsed knife meshes when the resource packs reload.
 *
 * <p>Without this, a pack that replaces a {@code .glb} would keep rendering the
 * old geometry until the game restarts, because the cache is keyed by style and
 * there is no reason for it to look again. Clearing on reload is what makes the
 * files hot swappable, which is the whole point of keeping them as data.
 *
 * <p>The parse runs on the reload's preparation executor, not on the render
 * thread and not before the reload has finished, so the game thread never waits
 * for it and it reads from the manager the new pack set installed. A failure is
 * caught: a broken pack costs the glTF path, not the reload.
 */
public final class GltfReloadListener implements IdentifiableResourceReloadListener {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("capy", "gltf_reload");

	@Override
	public Identifier getFabricId() {
		return ID;
	}

	@Override
	public CompletableFuture<Void> reload(PreparableReloadListener.SharedState state,
			Executor preparationExecutor, PreparableReloadListener.PreparationBarrier barrier,
			Executor gameExecutor) {
		return CompletableFuture.supplyAsync(() -> {
					// A throw here fails the reload future, and the game comes back
					// from F3+T with missing models, sounds and fonts. A knife cache
					// is never worth that, so the failure is contained to the knife.
					try {
						GltfCache.invalidate();
						CustomKnifeModule module = ModuleManager.get().get(CustomKnifeModule.class);
						if (module != null && module.isEnabled()) {
							preload(state.resourceManager());
						}
					} catch (Throwable failure) {
						GltfLog.reloadFailure(failure);
					}
					return Boolean.TRUE;
				}, preparationExecutor)
				// The textures and models this listener does not own are loaded
				// on the same barrier; joining it means the meshes never come
				// from a half installed pack set.
				.thenCompose(done -> barrier.wait(done))
				.thenRunAsync(() -> {
				}, gameExecutor);
	}

	private static void preload(ResourceManager manager) {
		try {
			GltfCache.preloadAll(manager);
		} catch (Throwable failure) {
			GltfLog.reloadFailure(failure);
		}
	}
}
