package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LightSignalQueueTest {
	@Test
	void classifierRecognisesZeroedPresentAndUniformMax() {
		assertEquals(LightSignalQueue.Kind.ZEROED_PRESENT, LightSignalQueue.classify(new byte[2048]));
		byte[] full = new byte[2048];
		java.util.Arrays.fill(full, (byte) 0xFF);
		assertEquals(LightSignalQueue.Kind.UNIFORM_MAX, LightSignalQueue.classify(full));
		byte[] mixed = new byte[2048];
		mixed[0] = 1;
		assertEquals(LightSignalQueue.Kind.NORMAL, LightSignalQueue.classify(mixed));
	}

	@Test
	void unknownOnMissingPayload() {
		assertEquals(LightSignalQueue.Kind.UNKNOWN, LightSignalQueue.classify(null));
		assertEquals(LightSignalQueue.Kind.UNKNOWN, LightSignalQueue.classify(new byte[0]));
	}
}
