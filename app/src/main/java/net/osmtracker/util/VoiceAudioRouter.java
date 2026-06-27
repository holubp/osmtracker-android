package net.osmtracker.util;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import net.osmtracker.OSMTracker;

import java.util.List;

public class VoiceAudioRouter {

	public interface Callback {
		void onReady(boolean bluetoothActive);

		void onFailed();
	}

	private static final String TAG = VoiceAudioRouter.class.getSimpleName();
	private static final long BLUETOOTH_ROUTE_CHECK_MS = 50;
	private static final long BLUETOOTH_ROUTE_COOLDOWN_MS = 2000;
	private static final int MAX_BLUETOOTH_ROUTE_TIMEOUT_MS = 10000;
	private static final int MAX_FINAL_BEEP_DELAY_MS = 5000;

	private final Context context;
	private final AudioManager audioManager;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private BroadcastReceiver scoReceiver;
	private AudioDeviceCallback audioDeviceCallback;
	private Callback pendingCallback;
	private Runnable pendingTimeout;
	private Runnable pendingReady;
	private Runnable pendingRelease;
	private String trackingSource = OSMTracker.Preferences.VAL_VOICEREC_AUDIO_SOURCE;
	private String audioFocusMode = OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS;
	private int bluetoothRouteTimeoutMs = Integer.parseInt(
			OSMTracker.Preferences.VAL_VOICEREC_START_BEEP_DELAY);
	private Object audioFocusRequest;
	private boolean audioFocusHeld;
	private boolean bluetoothActive;
	private boolean tracking;
	private boolean warmUpEnabled;
	private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener =
			focusChange -> {
				if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
					audioFocusHeld = false;
				}
			};

	public VoiceAudioRouter(Context context) {
		this.context = context.getApplicationContext();
		audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
	}

	public void startTracking(SharedPreferences preferences) {
		tracking = true;
		trackingSource = getAudioSource(preferences);
		audioFocusMode = getAudioFocusMode(preferences);
		bluetoothRouteTimeoutMs = getBluetoothRouteTimeout(preferences);
		warmUpEnabled = isAudioFocusForTracking(preferences);

		if (!isBluetoothSource(trackingSource)) {
			release();
			return;
		}

		registerAudioDeviceCallback();
		if (isAudioFocusForTracking(preferences) && !requestVoiceAudioFocus()) {
			Log.w(TAG, "Could not obtain Bluetooth voice audio focus");
		}
		if (warmUpEnabled) {
			warmUp();
		}
	}

	public void stopTracking() {
		tracking = false;
		warmUpEnabled = false;
		unregisterAudioDeviceCallback();
		release();
	}

	public void warmUp() {
		if (!tracking || !warmUpEnabled || !isBluetoothSource(trackingSource)) {
			return;
		}

		prepareForRecording(trackingSource, new Callback() {
			@Override
			public void onReady(boolean bluetoothActive) {
				// The route is kept warm for the next recording.
			}

			@Override
			public void onFailed() {
				// Retry when the next recording starts.
			}
		});
	}

	public void prepareForRecording(String source, Callback callback) {
		cancelPending();

		if (!isBluetoothSource(source)) {
			bluetoothActive = false;
			callback.onReady(false);
			return;
		}

		if (!hasBluetoothPermission()) {
			handleBluetoothFailure(source, callback);
			return;
		}

		if (usesAudioFocus() && !requestVoiceAudioFocus()) {
			Log.w(TAG, "Could not obtain Bluetooth voice audio focus");
			handleBluetoothFailure(source, callback);
			return;
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			prepareCommunicationDevice(source, callback);
		} else {
			prepareBluetoothSco(source, callback);
		}
	}

	public void release() {
		cancelPending();
		releaseRoute();
	}

	public void finishRecording(SharedPreferences preferences) {
		if (isAudioFocusForTracking(preferences)) {
			return;
		}

		if (!bluetoothActive) {
			release();
			return;
		}

		pendingRelease = () -> {
			pendingRelease = null;
			releaseRoute();
		};
		handler.postDelayed(pendingRelease, BLUETOOTH_ROUTE_COOLDOWN_MS);
	}

	private void releaseRoute() {
		bluetoothActive = false;
		clearAudioRoute();
		abandonVoiceAudioFocus();
	}

	private void clearAudioRoute() {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				audioManager.clearCommunicationDevice();
			} else {
				audioManager.setBluetoothScoOn(false);
				audioManager.stopBluetoothSco();
			}
			audioManager.setMode(AudioManager.MODE_NORMAL);
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to release Bluetooth audio route", e);
		}
	}

	public boolean isBluetoothActive() {
		return bluetoothActive;
	}

	public static String getAudioSource(SharedPreferences preferences) {
		return preferences.getString(
				OSMTracker.Preferences.KEY_VOICEREC_AUDIO_SOURCE,
				OSMTracker.Preferences.VAL_VOICEREC_AUDIO_SOURCE);
	}

	public static boolean isBluetoothSource(String source) {
		return OSMTracker.Preferences.VAL_VOICEREC_AUDIO_SOURCE_BLUETOOTH_PREFERRED.equals(source)
				|| OSMTracker.Preferences.VAL_VOICEREC_AUDIO_SOURCE_BLUETOOTH_REQUIRED.equals(source);
	}

	public static boolean isBluetoothRequired(String source) {
		return OSMTracker.Preferences.VAL_VOICEREC_AUDIO_SOURCE_BLUETOOTH_REQUIRED.equals(source);
	}

	public static boolean requiresBluetoothPermission(SharedPreferences preferences) {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				&& isBluetoothSource(getAudioSource(preferences));
	}

	public static String getAudioFocusMode(SharedPreferences preferences) {
		String mode = preferences.getString(
				OSMTracker.Preferences.KEY_VOICEREC_AUDIO_FOCUS,
				OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS);
		if (OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_RECORDING.equals(mode)
				|| OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_TRACKING.equals(mode)) {
			return mode;
		}
		return OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_NONE;
	}

	public static boolean isAudioFocusForTracking(SharedPreferences preferences) {
		return OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_TRACKING.equals(
				getAudioFocusMode(preferences));
	}

	public static int getBluetoothRouteTimeout(SharedPreferences preferences) {
		return getIntPreference(
				preferences,
				OSMTracker.Preferences.KEY_VOICEREC_START_BEEP_DELAY,
				OSMTracker.Preferences.VAL_VOICEREC_START_BEEP_DELAY,
				0,
				MAX_BLUETOOTH_ROUTE_TIMEOUT_MS);
	}

	public static int getFinalBeepDelay(SharedPreferences preferences) {
		return getIntPreference(
				preferences,
				OSMTracker.Preferences.KEY_VOICEREC_FINAL_BEEP_DELAY,
				OSMTracker.Preferences.VAL_VOICEREC_FINAL_BEEP_DELAY,
				0,
				MAX_FINAL_BEEP_DELAY_MS);
	}

	public static int getStartBeepVolume(SharedPreferences preferences) {
		return getIntPreference(
				preferences,
				OSMTracker.Preferences.KEY_VOICEREC_START_BEEP_VOLUME,
				OSMTracker.Preferences.VAL_VOICEREC_START_BEEP_VOLUME,
				0,
				100);
	}

	public static int getFinalBeepVolume(SharedPreferences preferences) {
		return getIntPreference(
				preferences,
				OSMTracker.Preferences.KEY_VOICEREC_FINAL_BEEP_VOLUME,
				OSMTracker.Preferences.VAL_VOICEREC_FINAL_BEEP_VOLUME,
				0,
				100);
	}

	public static int getStartBeepDelay(SharedPreferences preferences) {
		return getBluetoothRouteTimeout(preferences);
	}

	private static int getIntPreference(
			SharedPreferences preferences, String key, String defaultValue, int min, int max) {
		try {
			int value = Integer.parseInt(preferences.getString(key, defaultValue));
			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException e) {
			return Integer.parseInt(defaultValue);
		}
	}

	public static boolean hasBluetoothPermission(Context context) {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
				|| context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
				== PackageManager.PERMISSION_GRANTED;
	}

	private boolean hasBluetoothPermission() {
		return hasBluetoothPermission(context);
	}

	private void prepareCommunicationDevice(String source, Callback callback) {
		try {
			AudioDeviceInfo currentDevice = audioManager.getCommunicationDevice();
			if (currentDevice != null && isBluetoothDevice(currentDevice)) {
				audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
				waitForBluetoothRoute(source, callback);
				return;
			}

			AudioDeviceInfo device = findBluetoothCommunicationDevice();
			if (device == null) {
				handleBluetoothFailure(source, callback);
				return;
			}

			audioManager.clearCommunicationDevice();
			audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
			if (audioManager.setCommunicationDevice(device)) {
				waitForBluetoothRoute(source, callback);
			} else {
				handleBluetoothFailure(source, callback);
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to prepare Bluetooth communication device", e);
			handleBluetoothFailure(source, callback);
		}
	}

	private AudioDeviceInfo findBluetoothCommunicationDevice() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
			return null;
		}

		List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
		for (AudioDeviceInfo device : devices) {
			if (isBluetoothDevice(device)) {
				return device;
			}
		}
		return null;
	}

	private void prepareBluetoothSco(String source, Callback callback) {
		if (bluetoothActive) {
			boolean frameworkScoOn = false;
			try {
				frameworkScoOn = audioManager.isBluetoothScoOn();
			} catch (RuntimeException e) {
				Log.w(TAG, "Failed to check Bluetooth SCO", e);
			}
			if (VoiceRecordingSequence.canReuseLegacyBluetoothSco(
					bluetoothActive, frameworkScoOn)) {
				try {
					audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
					notifyBluetoothReady(callback);
					return;
				} catch (RuntimeException e) {
					Log.w(TAG, "Failed to reuse Bluetooth SCO", e);
					bluetoothActive = false;
				}
			}
			bluetoothActive = false;
		}

		pendingCallback = callback;
		registerScoReceiver();
		pendingTimeout = () -> {
			Log.w(TAG, "Timed out while waiting for Bluetooth SCO");
			cancelPending();
			handleBluetoothFailure(source, callback);
		};
		handler.postDelayed(pendingTimeout, bluetoothRouteTimeoutMs);

		try {
			audioManager.setBluetoothScoOn(false);
			audioManager.stopBluetoothSco();
			audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
			audioManager.startBluetoothSco();
			audioManager.setBluetoothScoOn(true);
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to start Bluetooth SCO", e);
			cancelPending();
			handleBluetoothFailure(source, callback);
		}
	}

	private void registerScoReceiver() {
		scoReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				int state = intent.getIntExtra(
						AudioManager.EXTRA_SCO_AUDIO_STATE,
						AudioManager.SCO_AUDIO_STATE_ERROR);
				if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
					Callback callback = pendingCallback;
					cancelPending();
					if (callback != null) {
						notifyBluetoothReady(callback);
					}
				} else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
						|| state == AudioManager.SCO_AUDIO_STATE_ERROR) {
					bluetoothActive = false;
				}
			}
		};
		IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			context.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
		} else {
			context.registerReceiver(scoReceiver, filter);
		}
	}

	private void registerAudioDeviceCallback() {
		if (audioDeviceCallback != null) {
			return;
		}

		audioDeviceCallback = new AudioDeviceCallback() {
			@Override
			public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
				for (AudioDeviceInfo device : addedDevices) {
					if (isBluetoothDevice(device)) {
						warmUp();
						break;
					}
				}
			}

			@Override
			public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
				for (AudioDeviceInfo device : removedDevices) {
					if (isBluetoothDevice(device)) {
						bluetoothActive = false;
						break;
					}
				}
			}
		};
		audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
	}

	private void unregisterAudioDeviceCallback() {
		if (audioDeviceCallback == null) {
			return;
		}
		audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
		audioDeviceCallback = null;
	}

	private boolean usesAudioFocus() {
		return !OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_NONE.equals(audioFocusMode);
	}

	private boolean requestVoiceAudioFocus() {
		if (audioFocusHeld) {
			return true;
		}

		int focusGain = OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_TRACKING.equals(audioFocusMode)
				? AudioManager.AUDIOFOCUS_GAIN
				: AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE;
		int result;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			AudioAttributes attributes = new AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build();
			AudioFocusRequest request = new AudioFocusRequest.Builder(focusGain)
					.setAudioAttributes(attributes)
					.setAcceptsDelayedFocusGain(false)
					.setOnAudioFocusChangeListener(audioFocusChangeListener, handler)
					.build();
			audioFocusRequest = request;
			result = audioManager.requestAudioFocus(request);
		} else {
			result = audioManager.requestAudioFocus(audioFocusChangeListener,
					AudioManager.STREAM_VOICE_CALL, focusGain);
		}

		audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
		return audioFocusHeld;
	}

	private void abandonVoiceAudioFocus() {
		if (!audioFocusHeld && audioFocusRequest == null) {
			return;
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
			audioManager.abandonAudioFocusRequest((AudioFocusRequest) audioFocusRequest);
			audioFocusRequest = null;
		} else {
			audioManager.abandonAudioFocus(audioFocusChangeListener);
		}
		audioFocusHeld = false;
	}

	private void waitForBluetoothRoute(String source, Callback callback) {
		pendingCallback = callback;
		long deadline = SystemClock.uptimeMillis() + bluetoothRouteTimeoutMs;
		pendingReady = new Runnable() {
			@Override
			public void run() {
				if (isBluetoothRouteReady()) {
					Callback readyCallback = pendingCallback;
					cancelPending();
					if (readyCallback != null) {
						notifyBluetoothReady(readyCallback);
					}
					return;
				}

				if (SystemClock.uptimeMillis() >= deadline) {
					Callback failedCallback = pendingCallback;
					cancelPending();
					if (failedCallback != null) {
						handleBluetoothFailure(source, failedCallback);
					}
					return;
				}

				handler.postDelayed(this, BLUETOOTH_ROUTE_CHECK_MS);
			}
		};
		pendingReady.run();
	}

	private void notifyBluetoothReady(Callback callback) {
		bluetoothActive = true;
		callback.onReady(true);
	}

	private boolean isBluetoothRouteReady() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
			return bluetoothActive;
		}

		try {
			return isBluetoothDevice(audioManager.getCommunicationDevice());
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to check Bluetooth audio route", e);
			return false;
		}
	}

	private void cancelPending() {
		if (pendingTimeout != null) {
			handler.removeCallbacks(pendingTimeout);
			pendingTimeout = null;
		}
		if (pendingReady != null) {
			handler.removeCallbacks(pendingReady);
			pendingReady = null;
		}
		if (pendingRelease != null) {
			handler.removeCallbacks(pendingRelease);
			pendingRelease = null;
		}
		pendingCallback = null;

		if (scoReceiver != null) {
			try {
				context.unregisterReceiver(scoReceiver);
			} catch (IllegalArgumentException ignored) {
				// Receiver was already unregistered.
			}
			scoReceiver = null;
		}
	}

	private void handleBluetoothFailure(String source, Callback callback) {
		bluetoothActive = false;
		clearAudioRoute();
		abandonVoiceAudioFocus();
		if (isBluetoothRequired(source)) {
			callback.onFailed();
		} else {
			callback.onReady(false);
		}
	}

	private boolean isBluetoothDevice(AudioDeviceInfo device) {
		if (device == null) {
			return false;
		}
		int type = device.getType();
		return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
				|| type == AudioDeviceInfo.TYPE_BLE_HEADSET;
	}
}
