package net.osmtracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import net.osmtracker.OSMTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;

import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class VoiceButtonPreferencesTest {

	private SharedPreferences preferences;

	@Before
	public void setUp() {
		preferences = PreferenceManager.getDefaultSharedPreferences(
				RuntimeEnvironment.getApplication());
		preferences.edit().clear().commit();
	}

	@Test
	public void addStoresMultipleKeyCodes() {
		VoiceButtonPreferences.add(preferences, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
		VoiceButtonPreferences.add(preferences, KeyEvent.KEYCODE_HEADSETHOOK);

		assertTrue(VoiceButtonPreferences.contains(
				preferences, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
		assertTrue(VoiceButtonPreferences.contains(
				preferences, KeyEvent.KEYCODE_HEADSETHOOK));
	}

	@Test
	public void clearDisablesConfiguredButtons() {
		VoiceButtonPreferences.add(preferences, KeyEvent.KEYCODE_HEADSETHOOK);

		VoiceButtonPreferences.clear(preferences);

		assertFalse(VoiceButtonPreferences.contains(
				preferences, KeyEvent.KEYCODE_HEADSETHOOK));
	}

	@Test
	public void invalidStoredValuesAreIgnored() {
		Set<String> values = new HashSet<>();
		values.add("bad");
		values.add(String.valueOf(KeyEvent.KEYCODE_HEADSETHOOK));
		preferences.edit()
				.putStringSet(OSMTracker.Preferences.KEY_VOICEREC_BUTTONS, values)
				.commit();

		assertTrue(VoiceButtonPreferences.contains(
				preferences, KeyEvent.KEYCODE_HEADSETHOOK));
		assertFalse(VoiceButtonPreferences.contains(preferences, KeyEvent.KEYCODE_CAMERA));
	}

	@Test
	public void bluetoothRouteTimeoutFallsBackWhenInvalid() {
		preferences.edit()
				.putString(OSMTracker.Preferences.KEY_VOICEREC_START_BEEP_DELAY, "bad")
				.commit();

		assertEquals(1000, VoiceAudioRouter.getBluetoothRouteTimeout(preferences));
	}

	@Test
	public void bluetoothRouteTimeoutIsClamped() {
		preferences.edit()
				.putString(OSMTracker.Preferences.KEY_VOICEREC_START_BEEP_DELAY, "20000")
				.commit();

		assertEquals(10000, VoiceAudioRouter.getBluetoothRouteTimeout(preferences));
	}

	@Test
	public void finalBeepDelayFallsBackWhenInvalid() {
		preferences.edit()
				.putString(OSMTracker.Preferences.KEY_VOICEREC_FINAL_BEEP_DELAY, "bad")
				.commit();

		assertEquals(500, VoiceAudioRouter.getFinalBeepDelay(preferences));
	}

	@Test
	public void finalBeepDelayIsClamped() {
		preferences.edit()
				.putString(OSMTracker.Preferences.KEY_VOICEREC_FINAL_BEEP_DELAY, "20000")
				.commit();

		assertEquals(5000, VoiceAudioRouter.getFinalBeepDelay(preferences));
	}

	@Test
	public void beepVolumesAreClamped() {
		preferences.edit()
				.putString(OSMTracker.Preferences.KEY_VOICEREC_START_BEEP_VOLUME, "-1")
				.putString(OSMTracker.Preferences.KEY_VOICEREC_FINAL_BEEP_VOLUME, "200")
				.commit();

		assertEquals(0, VoiceAudioRouter.getStartBeepVolume(preferences));
		assertEquals(100, VoiceAudioRouter.getFinalBeepVolume(preferences));
	}

	@Test
	public void audioFocusModeFallsBackWhenInvalid() {
		preferences.edit()
				.putString(OSMTracker.Preferences.KEY_VOICEREC_AUDIO_FOCUS, "bad")
				.commit();

		assertEquals(OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_NONE,
				VoiceAudioRouter.getAudioFocusMode(preferences));
	}
}
