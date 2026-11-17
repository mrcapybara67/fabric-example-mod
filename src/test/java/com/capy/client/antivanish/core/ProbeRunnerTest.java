package com.capy.client.antivanish.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbeRunnerTest {
	private static Random noJitter() {
		return new Random() {
			@Override
			public int nextInt(int bound) {
				return 0;
			}
		};
	}

	@Test
	void probeOffSendsNothing() {
		List<String> sent = new ArrayList<>();
		ProbeRunner runner = new ProbeRunner(new ProbePlanner(noJitter()), command -> {
			sent.add(command);
			return 7;
		});
		runner.setNames(List.of("Alpha"));
		runner.tick(false, 0L, Set.of());
		assertTrue(sent.isEmpty());
		assertFalse(runner.pending());
	}

	@Test
	void sendsOneRequestAtATimeAndOnlyTheMatchingExactAnswerCompletesIt() {
		List<String> sent = new ArrayList<>();
		int[] nextId = {7};
		ProbeRunner runner = new ProbeRunner(new ProbePlanner(noJitter()), command -> {
			sent.add(command);
			return nextId[0]++;
		});
		runner.setNames(List.of("Alpha", "Bravo"));
		runner.tick(true, 0L, Set.of());
		assertEquals(List.of("msg Alpha"), sent);
		runner.tick(true, 1L, Set.of());
		assertEquals(1, sent.size());
		assertNull(runner.acceptResponse(8, List.of("Alpha"), 2L));
		assertTrue(runner.pending());
		assertNull(runner.acceptResponse(7, List.of("Alphabet"), 3L));
		assertFalse(runner.pending());
		runner.tick(true, 25_000L, Set.of());
		assertEquals(List.of("msg Alpha", "msg Bravo"), sent);
		assertEquals("Bravo", runner.acceptResponse(8, List.of("Bravo"), 26_000L));
	}

	@Test
	void aTimedOutRequestDoesNotAcceptALateAnswer() {
		ProbeRunner runner = new ProbeRunner(new ProbePlanner(noJitter()), command -> 11);
		runner.setNames(List.of("Alpha"));
		runner.tick(true, 0L, Set.of());
		assertNull(runner.acceptResponse(11, List.of("Alpha"), ProbeRunner.TIMEOUT_MS + 1L));
		assertFalse(runner.pending());
	}
}
