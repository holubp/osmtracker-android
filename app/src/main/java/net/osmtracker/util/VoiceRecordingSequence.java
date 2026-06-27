package net.osmtracker.util;

public final class VoiceRecordingSequence {

	private VoiceRecordingSequence() {
	}

	public static boolean startsRecorderBeforeStartBeep(boolean bluetoothRoute) {
		return bluetoothRoute;
	}

	public static boolean stopsRecorderBeforeFinalBeep(boolean bluetoothRoute) {
		return !bluetoothRoute;
	}

	public static int getFinalBeepStopDelayMs(
			int finalBeepDurationMs, int finalBeepDelayMs, int bufferMs) {
		return Math.max(0, finalBeepDurationMs) + Math.max(0, finalBeepDelayMs)
				+ Math.max(0, bufferMs);
	}

	public static boolean canReuseLegacyBluetoothSco(
			boolean bluetoothActive, boolean frameworkScoOn) {
		return bluetoothActive && frameworkScoOn;
	}
}
