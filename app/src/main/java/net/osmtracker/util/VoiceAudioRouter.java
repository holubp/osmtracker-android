package net.osmtracker.util;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import net.osmtracker.OSMTracker;

import java.util.List;

public class VoiceAudioRouter {

	public interface Callback {
		void onReady(boolean bluetoothActive);

		void onFailed();
	}

	private static final String TAG = VoiceAudioRouter.class.getSimpleName();
	private static final long BLUETOOTH_SCO_TIMEOUT_MS = 5000;
	private static final long BLUETOOTH_COMMUNICATION_DEVICE_SETTLE_MS = 1000;

	private final Context context;
	private final AudioManager audioManager;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private BroadcastReceiver scoReceiver;
	private AudioDeviceCallback audioDeviceCallback;
	private Callback pendingCallback;
	private Runnable pendingTimeout;
	private Runnable pendingReady;
	private String trackingSource = OSMTracker.Preferences.VAL_VOICEREC_AUDIO_SOURCE;
	private boolean bluetoothActive;
	private boolean tracking;
	private boolean warmUpEnabled;

	public VoiceAudioRouter(Context context) {
		this.context = context.getApplicationContext();
		audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
	}

	public void startTracking(SharedPreferences preferences) {
		tracking = true;
		trackingSource = getAudioSource(preferences);
		// Keep headset media buttons available when they are used to start voice recordings.
		warmUpEnabled = VoiceButtonPreferences.getKeyCodes(preferences).isEmpty();

		if (!isBluetoothSource(trackingSource)) {
			release();
			return;
		}

		registerAudioDeviceCallback();
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

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			prepareCommunicationDevice(source, callback);
		} else {
			prepareBluetoothSco(source, callback);
		}
	}

	public void release() {
		cancelPending();
		bluetoothActive = false;
		clearAudioRoute();
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
				bluetoothActive = true;
				callback.onReady(true);
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
				pendingCallback = callback;
				pendingReady = () -> {
					Callback readyCallback = pendingCallback;
					pendingCallback = null;
					pendingReady = null;
					bluetoothActive = true;
					if (readyCallback != null) {
						readyCallback.onReady(true);
					}
				};
				handler.postDelayed(pendingReady, BLUETOOTH_COMMUNICATION_DEVICE_SETTLE_MS);
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
		pendingCallback = callback;
		registerScoReceiver();
		pendingTimeout = () -> {
			Log.w(TAG, "Timed out while waiting for Bluetooth SCO");
			cancelPending();
			handleBluetoothFailure(source, callback);
		};
		handler.postDelayed(pendingTimeout, BLUETOOTH_SCO_TIMEOUT_MS);

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
					bluetoothActive = true;
					if (callback != null) {
						callback.onReady(true);
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

	private void cancelPending() {
		if (pendingTimeout != null) {
			handler.removeCallbacks(pendingTimeout);
			pendingTimeout = null;
		}
		if (pendingReady != null) {
			handler.removeCallbacks(pendingReady);
			pendingReady = null;
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
		if (isBluetoothRequired(source)) {
			callback.onFailed();
		} else {
			callback.onReady(false);
		}
	}

	private boolean isBluetoothDevice(AudioDeviceInfo device) {
		int type = device.getType();
		return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
				|| type == AudioDeviceInfo.TYPE_BLE_HEADSET;
	}
}
