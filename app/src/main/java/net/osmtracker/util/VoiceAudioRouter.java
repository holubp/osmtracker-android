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

	/** Receives route loss while a Bluetooth recording is still active. */
	public interface RouteListener {
		void onBluetoothRouteLost();
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
	private AudioManager.OnCommunicationDeviceChangedListener communicationDeviceListener;
	private AudioManager.OnModeChangedListener audioModeListener;
	private Callback pendingCallback;
	private Runnable pendingTimeout;
	private Runnable pendingReady;
	private Runnable pendingRelease;
	private RouteListener routeListener;
	private String trackingSource = OSMTracker.Preferences.VAL_VOICEREC_AUDIO_SOURCE;
	private String audioFocusMode = OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS;
	private int bluetoothRouteTimeoutMs = Integer.parseInt(
			OSMTracker.Preferences.VAL_VOICEREC_START_BEEP_DELAY);
	private Object audioFocusRequest;
	private boolean audioFocusHeld;
	private boolean bluetoothActive;
	private int bluetoothDeviceId = -1;
	private boolean tracking;
	private boolean warmUpEnabled;
	private boolean modeOwned;
	private boolean recordingLease;
	private boolean releaseWhenRecordingFinished;
	private long operationGeneration;
	private long pendingGeneration;
	private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener =
			focusChange -> {
				if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
					audioFocusHeld = true;
				} else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
					audioFocusHeld = false;
					handlePermanentAudioFocusLoss();
				} else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
						|| focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
					audioFocusHeld = false;
					if (!canTakeCommunicationMode()) {
						handlePermanentAudioFocusLoss();
					}
				}
			};

	public VoiceAudioRouter(Context context) {
		this.context = context.getApplicationContext();
		audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
	}

	public void startTracking(SharedPreferences preferences) {
		tracking = true;
		releaseWhenRecordingFinished = false;
		trackingSource = getAudioSource(preferences);
		audioFocusMode = getAudioFocusMode(preferences);
		bluetoothRouteTimeoutMs = getBluetoothRouteTimeout(preferences);
		warmUpEnabled = isAudioFocusForTracking(preferences);

		if (!isBluetoothSource(trackingSource)) {
			unregisterAudioDeviceCallback();
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
		if (recordingLease) {
			releaseWhenRecordingFinished = true;
			return;
		}
		unregisterAudioDeviceCallback();
		release();
	}

	public void warmUp() {
		if (!tracking || !warmUpEnabled || recordingLease || !isBluetoothSource(trackingSource)) {
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
		}, false);
	}

	public void prepareForRecording(String source, Callback callback) {
		prepareForRecording(source, callback, true);
	}

	private void prepareForRecording(
			String source, Callback callback, boolean acquireRecordingLease) {
		long generation = beginOperation();

		if (!isBluetoothSource(source)) {
			bluetoothActive = false;
			callback.onReady(false);
			return;
		}
		if (acquireRecordingLease) {
			recordingLease = true;
		}
		registerAudioDeviceCallback();

		if (!hasBluetoothPermission()) {
			handleRouteFailure(generation, source, callback,
					"Bluetooth voice recording permission is not granted");
			return;
		}

		if (!canTakeCommunicationMode()) {
			Log.w(TAG, "Audio mode is already owned by another call or communication client");
			handleAudioBusy(generation, callback);
			return;
		}
		if (usesAudioFocus() && !requestVoiceAudioFocus()) {
			Log.w(TAG, "Could not obtain Bluetooth voice audio focus");
			handleFocusFailure(generation, callback, "Bluetooth voice audio focus was not granted");
			return;
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			prepareCommunicationDevice(generation, source, callback);
		} else {
			prepareBluetoothSco(generation, source, callback);
		}
	}

	/** Reacquires focus and confirms the existing route before a cue is played. */
	public void revalidateForCue(String source, Callback callback) {
		if (!isBluetoothSource(source)) {
			callback.onReady(false);
			return;
		}
		if (!isCommunicationModeReady()) {
			callback.onFailed();
			return;
		}
		if (usesAudioFocus() && !requestVoiceAudioFocus()) {
			callback.onFailed();
			return;
		}
		if (!isBluetoothRouteReady()) {
			callback.onFailed();
			return;
		}
		callback.onReady(true);
	}

	public void setRouteListener(RouteListener listener) {
		routeListener = listener;
	}

	public void release() {
		beginOperation();
		recordingLease = false;
		releaseWhenRecordingFinished = false;
		releaseRoute();
	}

	public void finishRecording() {
		recordingLease = false;
		if (releaseWhenRecordingFinished || !tracking) {
			releaseWhenRecordingFinished = false;
			unregisterAudioDeviceCallback();
			release();
			return;
		}
		if (!bluetoothActive) {
			release();
			return;
		}
		if (OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_TRACKING.equals(audioFocusMode)) {
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
		bluetoothDeviceId = -1;
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
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to release Bluetooth audio route", e);
		}
		if (modeOwned) {
			try {
				audioManager.setMode(AudioManager.MODE_NORMAL);
			} catch (RuntimeException e) {
				Log.w(TAG, "Failed to release communication audio mode", e);
			}
		}
		modeOwned = false;
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

	private void prepareCommunicationDevice(long generation, String source, Callback callback) {
		try {
			setCommunicationMode();
			waitForCommunicationDevice(generation, source, callback);
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to prepare Bluetooth communication device", e);
			handleRouteFailure(generation, source, callback,
					"Bluetooth communication device preparation failed");
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

	private void prepareBluetoothSco(long generation, String source, Callback callback) {
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
					setCommunicationMode();
					notifyBluetoothReady(generation, callback);
					return;
				} catch (RuntimeException e) {
					Log.w(TAG, "Failed to reuse Bluetooth SCO", e);
					bluetoothActive = false;
				}
			}
			bluetoothActive = false;
		}

		pendingCallback = callback;
		pendingGeneration = generation;
		registerScoReceiver();
		pendingTimeout = () -> {
			if (!isCurrent(generation)) {
				return;
			}
			Log.w(TAG, "Timed out while waiting for Bluetooth SCO");
			cancelPending();
			handleRouteFailure(generation, source, callback, "Timed out waiting for Bluetooth SCO");
		};
		handler.postDelayed(pendingTimeout, bluetoothRouteTimeoutMs);

		try {
			audioManager.setBluetoothScoOn(false);
			audioManager.stopBluetoothSco();
			setCommunicationMode();
			audioManager.startBluetoothSco();
			audioManager.setBluetoothScoOn(true);
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to start Bluetooth SCO", e);
			cancelPending();
			handleRouteFailure(generation, source, callback, "Failed to start Bluetooth SCO");
		}
	}

	private void registerScoReceiver() {
		if (scoReceiver != null) {
			return;
		}
		scoReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				int state = intent.getIntExtra(
						AudioManager.EXTRA_SCO_AUDIO_STATE,
						AudioManager.SCO_AUDIO_STATE_ERROR);
				if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
					if (!isCurrent(pendingGeneration)) {
						return;
					}
					Callback callback = pendingCallback;
					if (callback == null || !isLegacyBluetoothRouteReady()) {
						return;
					}
					long generation = pendingGeneration;
					cancelPending();
					notifyBluetoothReady(generation, callback);
				} else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
						|| state == AudioManager.SCO_AUDIO_STATE_ERROR) {
					if (bluetoothActive) {
						handleBluetoothRouteLost();
					}
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
					if (isBluetoothDevice(device)
							&& (bluetoothDeviceId == -1 || device.getId() == bluetoothDeviceId)) {
						handleBluetoothRouteLost();
						break;
					}
				}
			}
		};
		audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			communicationDeviceListener = device -> {
				if (bluetoothActive && (!isBluetoothDevice(device)
						|| (bluetoothDeviceId != -1 && device.getId() != bluetoothDeviceId))) {
					handleBluetoothRouteLost();
				}
			};
			audioManager.addOnCommunicationDeviceChangedListener(handler::post,
					communicationDeviceListener);
			audioModeListener = mode -> {
				if (bluetoothActive && modeOwned
						&& mode != AudioManager.MODE_IN_COMMUNICATION) {
					modeOwned = false;
					handleBluetoothRouteLost();
				}
			};
			audioManager.addOnModeChangedListener(handler::post, audioModeListener);
		}
	}

	private void unregisterAudioDeviceCallback() {
		if (audioDeviceCallback != null) {
			audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
			audioDeviceCallback = null;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceListener != null) {
			audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener);
			communicationDeviceListener = null;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audioModeListener != null) {
			audioManager.removeOnModeChangedListener(audioModeListener);
			audioModeListener = null;
		}
		unregisterScoReceiver();
	}

	private boolean usesAudioFocus() {
		return !OSMTracker.Preferences.VAL_VOICEREC_AUDIO_FOCUS_NONE.equals(audioFocusMode);
	}

	private boolean requestVoiceAudioFocus() {
		if (audioFocusHeld) {
			return true;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
			audioManager.abandonAudioFocusRequest((AudioFocusRequest) audioFocusRequest);
			audioFocusRequest = null;
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

	private void waitForCommunicationDevice(long generation, String source, Callback callback) {
		pendingCallback = callback;
		pendingGeneration = generation;
		long deadline = SystemClock.uptimeMillis() + bluetoothRouteTimeoutMs;
		pendingReady = new Runnable() {
			private boolean selectionFailureLogged;

			@Override
			public void run() {
				if (!isCurrent(generation)) {
					return;
				}
				if (!canTakeCommunicationMode()) {
					Callback busyCallback = pendingCallback;
					cancelPending();
					if (busyCallback != null) {
						handleAudioBusy(generation, busyCallback);
					}
					return;
				}
				try {
					if (isBluetoothRouteReady()) {
						Callback readyCallback = pendingCallback;
						cancelPending();
						if (readyCallback != null) {
							notifyBluetoothReady(generation, readyCallback);
						}
						return;
					}

					AudioDeviceInfo device = findBluetoothCommunicationDevice();
					if (device != null) {
						setCommunicationMode();
						boolean selected = audioManager.setCommunicationDevice(device);
						if (!selected && !selectionFailureLogged) {
							selectionFailureLogged = true;
							Log.w(TAG, "Failed to select Bluetooth communication device: "
									+ describeCommunicationDevices());
						}
					}
				} catch (RuntimeException e) {
					Log.w(TAG, "Failed while waiting for Bluetooth communication device", e);
				}

				if (SystemClock.uptimeMillis() >= deadline) {
					Callback failedCallback = pendingCallback;
					cancelPending();
					if (failedCallback != null) {
						handleRouteFailure(generation, source, failedCallback,
								"Timed out waiting for Bluetooth communication device: "
										+ describeCommunicationDevices());
					}
					return;
				}

				handler.postDelayed(this, BLUETOOTH_ROUTE_CHECK_MS);
			}
		};
		pendingReady.run();
	}

	private void notifyBluetoothReady(long generation, Callback callback) {
		if (!isCurrent(generation)) {
			return;
		}
		if (!isCommunicationModeReady()) {
			handleAudioBusy(generation, callback);
			return;
		}
		bluetoothActive = true;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			try {
				AudioDeviceInfo device = audioManager.getCommunicationDevice();
				bluetoothDeviceId = device == null ? -1 : device.getId();
			} catch (RuntimeException e) {
				Log.w(TAG, "Could not remember the Bluetooth communication device", e);
				bluetoothDeviceId = -1;
			}
		}
		callback.onReady(true);
	}

	private boolean isBluetoothRouteReady() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
			return bluetoothActive && isLegacyBluetoothRouteReady();
		}

		try {
			AudioDeviceInfo device = audioManager.getCommunicationDevice();
			return isCommunicationModeReady()
					&& isBluetoothDevice(device)
					&& (bluetoothDeviceId == -1 || device.getId() == bluetoothDeviceId);
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to check Bluetooth audio route", e);
			return false;
		}
	}

	private boolean isLegacyBluetoothRouteReady() {
		try {
			return audioManager.isBluetoothScoOn();
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to check legacy Bluetooth SCO route", e);
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

	}

	private void unregisterScoReceiver() {
		if (scoReceiver == null) {
			return;
		}
		try {
			context.unregisterReceiver(scoReceiver);
		} catch (IllegalArgumentException ignored) {
			// Receiver was already unregistered.
		}
		scoReceiver = null;
	}

	private long beginOperation() {
		operationGeneration++;
		cancelPending();
		return operationGeneration;
	}

	private boolean isCurrent(long generation) {
		return generation == operationGeneration;
	}

	private boolean canTakeCommunicationMode() {
		try {
			int mode = audioManager.getMode();
			return mode == AudioManager.MODE_NORMAL
					|| (mode == AudioManager.MODE_IN_COMMUNICATION && modeOwned);
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to inspect the current audio mode", e);
			return false;
		}
	}

	private boolean isCommunicationModeReady() {
		try {
			return modeOwned && audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION;
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to verify the current audio mode", e);
			return false;
		}
	}

	private void setCommunicationMode() {
		audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
		modeOwned = true;
	}

	private void handleFocusFailure(long generation, Callback callback, String reason) {
		if (!isCurrent(generation)) {
			return;
		}
		Log.w(TAG, reason);
		recordingLease = false;
		releaseRoute();
		callback.onFailed();
	}

	private void handlePermanentAudioFocusLoss() {
		if (!isCommunicationModeReady()) {
			modeOwned = false;
		}
		if (pendingCallback != null) {
			Callback callback = pendingCallback;
			beginOperation();
			recordingLease = false;
			releaseRoute();
			callback.onFailed();
			return;
		}
		if (bluetoothActive) {
			handleBluetoothRouteLost();
		} else if (modeOwned || recordingLease) {
			recordingLease = false;
			releaseRoute();
		}
	}

	private void handleAudioBusy(long generation, Callback callback) {
		if (!isCurrent(generation)) {
			return;
		}
		recordingLease = false;
		modeOwned = false;
		releaseRoute();
		callback.onFailed();
	}

	private void handleRouteFailure(long generation, String source, Callback callback, String reason) {
		if (!isCurrent(generation)) {
			return;
		}
		if (reason != null) {
			Log.w(TAG, reason);
		}
		bluetoothActive = false;
		recordingLease = false;
		clearAudioRoute();
		abandonVoiceAudioFocus();
		if (isBluetoothRequired(source)) {
			callback.onFailed();
		} else {
			waitForPhoneRoute(generation, callback);
		}
	}

	private void waitForPhoneRoute(long generation, Callback callback) {
		pendingCallback = callback;
		pendingGeneration = generation;
		long deadline = SystemClock.uptimeMillis() + bluetoothRouteTimeoutMs;
		pendingReady = new Runnable() {
			@Override
			public void run() {
				if (!isCurrent(generation)) {
					return;
				}
				if (isPhoneRouteReady()) {
					Callback readyCallback = pendingCallback;
					cancelPending();
					if (readyCallback != null) {
						readyCallback.onReady(false);
					}
					return;
				}
				if (SystemClock.uptimeMillis() >= deadline) {
					Callback failedCallback = pendingCallback;
					cancelPending();
					Log.w(TAG, "Timed out while releasing the Bluetooth communication route");
					if (failedCallback != null) {
						failedCallback.onFailed();
					}
					return;
				}
				handler.postDelayed(this, BLUETOOTH_ROUTE_CHECK_MS);
			}
		};
		pendingReady.run();
	}

	private boolean isPhoneRouteReady() {
		try {
			boolean bluetoothRouteReleased = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
					? !isBluetoothDevice(audioManager.getCommunicationDevice())
					: !audioManager.isBluetoothScoOn();
			return bluetoothRouteReleased && audioManager.getMode() == AudioManager.MODE_NORMAL;
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to verify Bluetooth route release", e);
			return false;
		}
	}

	private void handleBluetoothRouteLost() {
		if (!bluetoothActive) {
			return;
		}
		beginOperation();
		bluetoothActive = false;
		if (recordingLease && routeListener != null) {
			routeListener.onBluetoothRouteLost();
		} else {
			recordingLease = false;
			releaseRoute();
		}
	}

	private String describeCommunicationDevices() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
			return "legacy SCO route";
		}

		try {
			StringBuilder builder = new StringBuilder();
			builder.append("current=");
			appendAudioDevice(builder, audioManager.getCommunicationDevice());
			builder.append(", available=");
			List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
			for (int i = 0; i < devices.size(); i++) {
				if (i > 0) {
					builder.append('|');
				}
				appendAudioDevice(builder, devices.get(i));
			}
			return builder.toString();
		} catch (RuntimeException e) {
			return "failed to inspect communication devices";
		}
	}

	private void appendAudioDevice(StringBuilder builder, AudioDeviceInfo device) {
		if (device == null) {
			builder.append("none");
			return;
		}
		builder.append("type=").append(device.getType());
		builder.append(",bt=").append(isBluetoothDevice(device));
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
