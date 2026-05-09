package net.osmtracker.util;

import android.content.SharedPreferences;
import android.view.KeyEvent;

import net.osmtracker.OSMTracker;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public final class VoiceButtonPreferences {

	private VoiceButtonPreferences() {
	}

	public static Set<Integer> getKeyCodes(SharedPreferences preferences) {
		Set<String> values = preferences.getStringSet(
				OSMTracker.Preferences.KEY_VOICEREC_BUTTONS,
				Collections.emptySet());
		Set<Integer> keyCodes = new TreeSet<>();

		for (String value : values) {
			try {
				keyCodes.add(Integer.parseInt(value));
			} catch (NumberFormatException ignored) {
				// Ignore values from older or manually edited preferences.
			}
		}

		return keyCodes;
	}

	public static boolean contains(SharedPreferences preferences, int keyCode) {
		return getKeyCodes(preferences).contains(keyCode);
	}

	public static void add(SharedPreferences preferences, int keyCode) {
		Set<String> values = new HashSet<>(preferences.getStringSet(
				OSMTracker.Preferences.KEY_VOICEREC_BUTTONS,
				Collections.emptySet()));
		values.add(String.valueOf(keyCode));
		preferences.edit()
				.putStringSet(OSMTracker.Preferences.KEY_VOICEREC_BUTTONS, values)
				.apply();
	}

	public static void clear(SharedPreferences preferences) {
		preferences.edit()
				.remove(OSMTracker.Preferences.KEY_VOICEREC_BUTTONS)
				.apply();
	}

	public static String getDisplayName(int keyCode) {
		String name = KeyEvent.keyCodeToString(keyCode);
		if (name == null || name.length() == 0) {
			return String.valueOf(keyCode);
		}
		return name;
	}
}
