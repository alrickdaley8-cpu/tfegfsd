package com.doomsday.nukes.detonation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DetonationStage} is the mod's wire format and its translation namespace at the same time:
 * {@code StageChangeS2CPacket} carries {@link DetonationStage#index()} as a varint, and every
 * user-visible stage name is {@code gui.doomsday.stage.<key>}. Both are position- and spelling-
 * sensitive in ways a rename does not catch, so they are pinned here. An index that drifts silently
 * mislabels every client's stage; a renamed key silently loses a translation.
 */
final class DetonationStageTest {
	@Test
	void indexMatchesOrdinalSoTheWireFormatStaysStable() {
		DetonationStage[] all = DetonationStage.values();
		for (int i = 0; i < all.length; i++) {
			assertEquals(i, all[i].index(),
				"index() is what the packet carries, and it must stay equal to the declaration order");
		}
		assertEquals(0, DetonationStage.STANDBY.index());
		assertEquals(1, DetonationStage.ARMING.index());
		assertEquals(2, DetonationStage.FLASH.index());
		assertEquals(8, DetonationStage.COMPLETE.index(),
			"COMPLETE must stay last: byIndex() falls back to it for anything out of range");
	}

	@Test
	void keysAreLowerSnakeUniqueAndComplete() {
		Set<String> seen = new HashSet<>();
		for (DetonationStage s : DetonationStage.values()) {
			assertTrue(s.key().matches("[a-z][a-z0-9_]*"), "bad key: " + s.key());
			assertTrue(seen.add(s.key()), "duplicate translation key " + s.key());
		}
		// The exact set, because assets/doomsday/lang/*.json is generated from this list: a stage added
		// here without the matching lang pair would render as its raw key in the HUD banner.
		assertEquals(Set.of("standby", "arming", "flash", "fireball", "shockwave", "mushroom_cloud",
			"fallout", "aftermath", "complete"),
			Arrays.stream(DetonationStage.values()).map(DetonationStage::key)
				.collect(Collectors.toSet()));
	}

	@Test
	void lookupByKeyIsLenientOnCaseAndWhitespace() {
		assertSame(DetonationStage.MUSHROOM_CLOUD, DetonationStage.byKey("mushroom_cloud"));
		assertSame(DetonationStage.MUSHROOM_CLOUD, DetonationStage.byKey("MUSHROOM_CLOUD"));
		assertSame(DetonationStage.MUSHROOM_CLOUD, DetonationStage.byKey("  mushroom_cloud  "));
		for (DetonationStage s : DetonationStage.values()) {
			assertSame(s, DetonationStage.byKey(s.key()));
		}
	}

	@Test
	void unknownKeyIsNullRatherThanADefault() {
		// A typo in `/doomsday preview <stage>` has to be reported, not quietly rendered as some other
		// stage — a debug command that lies about which phase it played is worse than one that errors.
		assertNull(DetonationStage.byKey("crater"));
		assertNull(DetonationStage.byKey("emp"));
		assertNull(DetonationStage.byKey(""));
		assertNull(DetonationStage.byKey(null));
	}

	@Test
	void byIndexSaturatesInsteadOfThrowing() {
		assertSame(DetonationStage.FLASH, DetonationStage.byIndex(2));
		assertSame(DetonationStage.COMPLETE, DetonationStage.byIndex(99));
		assertSame(DetonationStage.COMPLETE, DetonationStage.byIndex(-1));
	}

	@Test
	void orderingIsMonotonicAndTotal() {
		DetonationStage[] all = DetonationStage.values();
		for (int i = 0; i < all.length; i++) {
			for (int j = 0; j < all.length; j++) {
				assertEquals(i >= j, all[i].atLeast(all[j]), all[i] + ".atLeast(" + all[j] + ")");
			}
		}
		assertTrue(DetonationStage.FALLOUT.atLeast(DetonationStage.FLASH));
		assertFalse(DetonationStage.FLASH.atLeast(DetonationStage.FALLOUT));
		assertTrue(DetonationStage.FLASH.atLeast(DetonationStage.FLASH), "atLeast is inclusive");
	}

	@Test
	void craterAndEmpAreNotStages() {
		// Locked in as a test because it is a design decision that a future contributor will otherwise
		// "fix": cratering happens inside FIREBALL (the terrain pass is driven by the same envelope) and
		// the EMP tail inside FALLOUT. Adding them as stages would split one atomic client event into
		// two packets and make the HUD announce a phase change that the simulation does not have.
		Set<String> keys = Arrays.stream(DetonationStage.values()).map(DetonationStage::key)
			.collect(Collectors.toSet());
		assertFalse(keys.contains("crater"));
		assertFalse(keys.contains("emp"));
	}
}
