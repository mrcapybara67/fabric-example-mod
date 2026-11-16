package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoundKindsTest {
	private static SoundKinds.Hit hit(String id) {
		return SoundKinds.classify(id);
	}

	@Test
	void naturalNoiseIsIgnored() {
		String[] ignored = {
				"minecraft:block.sand.break", "block.gravel.place", "block.anvil.land",
				"block.suspicious_sand.break", "block.pointed_dripstone.land",
				"block.oak_door.open", "block.wooden_door.close", "block.oak_trapdoor.open",
				"block.oak_fence_gate.open", "block.copper_door.open", "block.copper_trapdoor.close",
				"block.trial_spawner.ambient", "block.vault.activate", "block.sculk_sensor.clicking",
				"block.lava.pop", "block.water.ambient", "block.note_block.harp",
				"block.amethyst_block.chime", "block.bell.resonate", "block.snow.break"
		};
		for (String id : ignored) {
			assertEquals(SoundKinds.Kind.IGNORE, hit(id).kind(), id);
			assertEquals(0.0, hit(id).weight(), 0.0, id);
		}
		assertEquals(SoundKinds.Kind.IGNORE, hit(null).kind());
	}

	@Test
	void structureSoundsAreRecognized() {
		assertTrue(SoundKinds.isStructureSound("minecraft:block.trial_spawner.spawn_mob"));
		assertTrue(SoundKinds.isStructureSound("block.ominous_vault.activate"));
		assertTrue(SoundKinds.isStructureSound("block.sculk_shrieker.shriek"));
		assertTrue(SoundKinds.isStructureSound("entity.warden.roar"));
		assertTrue(SoundKinds.isStructureSound("block.bell.resonate"));
		assertTrue(SoundKinds.isStructureSound("block.end_portal.spawn"));
		assertTrue(SoundKinds.isStructureSound("block.spawner.spawn"));
		assertFalse(SoundKinds.isStructureSound("block.stone.break"));
		assertFalse(SoundKinds.isStructureSound(null));
		assertEquals(SoundKinds.Kind.IGNORE, hit("block.trial_spawner.spawn_mob").kind());
	}

	@Test
	void storageAndRedstoneKeepTheirWeights() {
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.STORAGE, 3.0), hit("block.chest.open"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.STORAGE, 3.0), hit("block.barrel.close"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.STORAGE, 3.0), hit("block.ender_chest.open"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.STORAGE, 3.0), hit("block.shulker_box.close"));
		assertEquals(SoundKinds.Kind.IGNORE, hit("block.chest.break").kind());

		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 2.0), hit("block.piston.extend"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 2.0), hit("block.piston.contract"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 2.0), hit("block.dispenser.dispense"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 2.0), hit("block.dispenser.launch"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 2.0), hit("block.dispenser.fail"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 1.0), hit("block.comparator.click"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 1.0), hit("block.lever.click"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 1.0), hit("block.stone_button.click_on"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 1.0), hit("block.oak_pressure_plate.click_on"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 1.0), hit("block.tripwire.click_on"));
	}

	@Test
	void ironDoorsStayAndWoodenOnesDoNot() {
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 1.5), hit("block.iron_door.open"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.REDSTONE, 1.5), hit("block.iron_trapdoor.close"));
		assertEquals(SoundKinds.Kind.IGNORE, hit("block.iron_door.break").kind());
		assertEquals(SoundKinds.Kind.IGNORE, hit("block.oak_door.open").kind());
		assertEquals(SoundKinds.Kind.IGNORE, hit("block.oak_trapdoor.close").kind());
		assertEquals(SoundKinds.Kind.IGNORE, hit("block.oak_fence_gate.open").kind());
	}

	@Test
	void miningBuildingAndMachinesAreClassified() {
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.MINING, 1.5), hit("block.deepslate.break"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.MINING, 1.5), hit("block.ancient_debris.break"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.MINING, 1.0), hit("block.oak_planks.break"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.MINING, 0.4), hit("block.stone.hit"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.BUILDING, 1.5), hit("block.tuff.place"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.BUILDING, 1.0), hit("block.oak_log.place"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.MACHINE, 1.0), hit("block.furnace.fire_crackle"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.MACHINE, 1.0), hit("block.blastfurnace.fire_crackle"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.MACHINE, 1.0), hit("block.smoker.fire_crackle"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.MACHINE, 1.0), hit("block.beacon.activate"));
		assertEquals(new SoundKinds.Hit(SoundKinds.Kind.EXPLOSION, 2.0), hit("entity.generic.explode"));
	}

	@Test
	void tntPrimedIsDetected() {
		assertTrue(SoundKinds.isTntPrimed("minecraft:entity.tnt.primed"));
		assertTrue(SoundKinds.isTntPrimed("entity.tnt.primed"));
		assertFalse(SoundKinds.isTntPrimed("entity.generic.explode"));
		assertFalse(SoundKinds.isTntPrimed(null));
	}
}
