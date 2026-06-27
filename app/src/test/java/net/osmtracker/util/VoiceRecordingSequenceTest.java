package net.osmtracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceRecordingSequenceTest {

	@Test
	public void bluetoothRouteStartsRecorderBeforeStartBeep() {
		assertTrue(VoiceRecordingSequence.startsRecorderBeforeStartBeep(true));
		assertFalse(VoiceRecordingSequence.startsRecorderBeforeStartBeep(false));
	}

	@Test
	public void phoneRouteStopsRecorderBeforeFinalBeep() {
		assertFalse(VoiceRecordingSequence.stopsRecorderBeforeFinalBeep(true));
		assertTrue(VoiceRecordingSequence.stopsRecorderBeforeFinalBeep(false));
	}

	@Test
	public void finalBeepStopDelayIncludesDurationPositiveDelayAndBuffer() {
		assertEquals(1350, VoiceRecordingSequence.getFinalBeepStopDelayMs(
				250, 100, 1000));
	}

	@Test
	public void finalBeepStopDelayIgnoresNegativeConfiguredDelay() {
		assertEquals(1250, VoiceRecordingSequence.getFinalBeepStopDelayMs(
				250, -100, 1000));
	}

	@Test
	public void legacyScoReuseRequiresInternalAndFrameworkState() {
		assertTrue(VoiceRecordingSequence.canReuseLegacyBluetoothSco(true, true));
		assertFalse(VoiceRecordingSequence.canReuseLegacyBluetoothSco(true, false));
		assertFalse(VoiceRecordingSequence.canReuseLegacyBluetoothSco(false, true));
		assertFalse(VoiceRecordingSequence.canReuseLegacyBluetoothSco(false, false));
	}
}
