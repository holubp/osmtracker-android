package net.osmtracker.view;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnInfoListener;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import net.osmtracker.OSMTracker;
import net.osmtracker.R;
import net.osmtracker.db.DataHelper;
import net.osmtracker.db.TrackContentProvider.Schema;
import net.osmtracker.util.VoiceAudioRouter;
import net.osmtracker.util.VoiceButtonPreferences;
import net.osmtracker.util.VoiceRecordingSequence;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class VoiceRecDialog extends ProgressDialog implements OnInfoListener {
	
	private final static String TAG = VoiceRecDialog.class.getSimpleName();

	private final static int FINAL_BEEP_STOP_BUFFER_MS = 1000;
	
	/**
	 * Id of the track the dialog will add this waypoint to
	 */
	private long wayPointTrackId;
	
	/**
	 * Unique identifier of the waypoint this dialog working on
	 */
	private String wayPointUuid = null;
	
	/**
	 * the duration of a voice recording in seconds
	 */
	private int recordingDuration = -1;

	/**
	 * Indicates if we are currently recording, to prevent double click.
	 */
	private boolean isRecording = false;
	
	/**
	 * MediaRecorder used to record audio
	 */
	private MediaRecorder mediaRecorder;

	private boolean recorderStarted = false;

	private boolean isStopping = false;

	private boolean voiceRouteStopped = false;

	private boolean bluetoothRecordingActive = false;

	private boolean bluetoothCaptureVerified = false;
	
	/**
	 * MediaPlayer used to play a short beepbeep when recording starts
	 */
	private MediaPlayer mediaPlayerStart = null;

	/**
	 * MediaPlayer used to play a short beep when recording stops
	 */
	private MediaPlayer mediaPlayerStop = null;

	private boolean playSound = false;

	private int finalBeepDelayMs = Integer.parseInt(
			OSMTracker.Preferences.VAL_VOICEREC_FINAL_BEEP_DELAY);

	private int startBeepVolume = Integer.parseInt(
			OSMTracker.Preferences.VAL_VOICEREC_START_BEEP_VOLUME);

	private int finalBeepVolume = Integer.parseInt(
			OSMTracker.Preferences.VAL_VOICEREC_FINAL_BEEP_VOLUME);

	private final Handler handler = new Handler(Looper.getMainLooper());

	private Runnable recordingTimeout;

	private Runnable stopRecorderAfterFinalBeep;

	private Runnable finalBeepStopFallback;

	private Runnable bluetoothRouteVerification;

	private AudioManager.AudioRecordingCallback recordingCallback;

	private MediaRecorder monitoredRecorder;

	private int bluetoothRecordingDeviceId = -1;

	private File audioFile;

	private String audioSource;

	private long recordingGeneration;
	
	/**
	 * the context for this dialog
	 */
	private Context context;

	private VoiceAudioRouter voiceAudioRouter;
	
	/**
	 * saves the orientation at the time when the dialog was started
	 */
	private int currentOrientation = -1;
	
	/**
	 * saves the requested orientation at the time when the dialog was started to restore it when we stop recording
	 */
	private int currentRequestedOrientation = -1;
	
	/**
	 * saves the time when this dialog was started.
	 * This is needed to check if a key was pressed before the dialog was shown 
	 */
	private long dialogStartTime = 0;
	
	public VoiceRecDialog(Context context, long trackId, VoiceAudioRouter voiceAudioRouter) {
		super(context);
		this.context = context;
		this.wayPointTrackId = trackId;
		this.voiceAudioRouter = voiceAudioRouter;
		this.setCancelable(false);
		
		this.setTitle(context.getResources().getString(R.string.tracklogger_voicerec_title));
		
		this.setButton(DialogInterface.BUTTON_NEGATIVE,
				context.getResources().getString(R.string.tracklogger_voicerec_stop),
				(DialogInterface.OnClickListener) null);
	}
	
	
	/**
	 * @link android.app.Dialog#onStart()
	 */
	@Override
	public void onStart() {
		// we'll need the start time of this dialog to check if a key has been pressed before the dialog was opened
		dialogStartTime = SystemClock.uptimeMillis();

		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

		if (!isRecording)
			recordingDuration = Integer.parseInt(
					preferences.getString(OSMTracker.Preferences.KEY_VOICEREC_DURATION,
						OSMTracker.Preferences.VAL_VOICEREC_DURATION));
		else {
			if (recordingDuration <= 0)
				recordingDuration = Integer.parseInt(OSMTracker.Preferences.VAL_VOICEREC_DURATION);
		}

		this.setMessage(
				context.getResources().getString(R.string.tracklogger_voicerec_text)
				.replace("{0}", String.valueOf(recordingDuration)));
		
		// we need to avoid screen orientation change during recording because this causes some strange behavior
		try{
			this.currentOrientation = context.getResources().getConfiguration().orientation;
			this.currentRequestedOrientation = this.getOwnerActivity().getRequestedOrientation();
			this.getOwnerActivity().setRequestedOrientation(currentOrientation);
		}catch(Exception e){
			Log.w(TAG, "No OwnerActivity found for this Dialog. Use showDialog method within the activity to handle this Dialog and to avoid voice recording problems.");
		}
		
		Log.d(TAG,"onStart() called");
		if(wayPointUuid == null){
			Log.d(TAG,"onStart() no UUID set, generating a new UUID");
			// there is no UUID set for the waypoint we're working on
			// so we need to generate a UUID and track this point
			wayPointUuid = UUID.randomUUID().toString();
			Intent intent = new Intent(OSMTracker.INTENT_TRACK_WP);
			intent.putExtra(Schema.COL_TRACK_ID, wayPointTrackId);
			intent.putExtra(OSMTracker.INTENT_KEY_UUID, wayPointUuid);
			intent.putExtra(OSMTracker.INTENT_KEY_NAME, context.getResources().getString(R.string.wpt_voicerec));
			intent.setPackage(getContext().getPackageName());
			context.sendBroadcast(intent);
		}
		
		if (!isRecording) {
			Log.d(TAG,"onStart() currently not recording, start a new one");
			
			isRecording = true;
			long generation = ++recordingGeneration;
			voiceRouteStopped = false;
			// Get a new audio filename
			audioFile = getAudioFile();

			if (audioFile != null) {

				playSound = preferences.getBoolean(OSMTracker.Preferences.KEY_SOUND_ENABLED,
						OSMTracker.Preferences.VAL_SOUND_ENABLED);
					finalBeepDelayMs = VoiceAudioRouter.getFinalBeepDelay(preferences);
					startBeepVolume = VoiceAudioRouter.getStartBeepVolume(preferences);
					finalBeepVolume = VoiceAudioRouter.getFinalBeepVolume(preferences);
					int bluetoothRouteTimeoutMs =
							VoiceAudioRouter.getBluetoothRouteTimeout(preferences);

					audioSource = VoiceAudioRouter.getAudioSource(preferences);
					long bluetoothRouteDeadline =
							SystemClock.uptimeMillis() + bluetoothRouteTimeoutMs;
					voiceAudioRouter.prepareForRecording(audioSource, new VoiceAudioRouter.Callback() {
						@Override
						public void onReady(boolean bluetoothActive) {
							if (isCurrentRecording(generation)) {
								prepareMediaRecorder(
										audioFile, bluetoothActive, bluetoothRouteDeadline);
							}
						}

						@Override
						public void onFailed() {
							if (isCurrentRecording(generation)) {
								failRecording();
							}
						}
				});
			} else {
				Log.w(TAG,"onStart() no suitable audioFile could be created");
				// The audio file could not be created on the file system
				// let the user know
				failRecording();
			}
		}

		super.onStart();

		Button stopButton = getButton(DialogInterface.BUTTON_NEGATIVE);
		if (stopButton != null) {
			stopButton.setOnClickListener(v -> stopRecording());
			sizeStopButton(stopButton);
		}
	}

	private void sizeStopButton(Button stopButton) {
		Activity activity = getOwnerActivity();
		if (activity == null && context instanceof Activity) {
			activity = (Activity) context;
		}
		if (activity == null) {
			return;
		}

		View appContent = activity.findViewById(android.R.id.content);
		if (appContent == null) {
			return;
		}
		appContent.post(() -> {
			if (!isShowing() || appContent.getHeight() <= 0) {
				return;
			}
			int minimumHeight = appContent.getHeight() / 4;
			ViewGroup.LayoutParams layoutParams = stopButton.getLayoutParams();
			layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
			layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
			stopButton.setMinimumHeight(minimumHeight);
			stopButton.setLayoutParams(layoutParams);
		});
	}
	
	@Override
	public void onInfo(MediaRecorder mr, int what, int extra) {
		if (mr != mediaRecorder) {
			return;
		}
		Log.d(TAG, "onInfo() received mediaRecorder info ("+String.valueOf(what)+")");
		switch(what){
		case MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN:
		case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
		case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
			stopRecording();
			break;
		}
	}

	private void onRecorderError(MediaRecorder mr, int what, int extra) {
		if (mr != mediaRecorder) {
			return;
		}
		Log.w(TAG, "MediaRecorder error (" + what + ", " + extra + ")");
		if (recorderStarted) {
			interruptRecording();
		} else {
			failRecording();
		}
	}
	
	/**
	 * called when the dialog disappears
	 */
	@Override
	protected void onStop() {
		Log.d(TAG, "onStop() called");

		cancelRecordingTimeout();
		cancelStopRecorderAfterFinalBeep();
		cancelFinalBeepStopFallback();
		cancelBluetoothRouteVerification();
		unregisterCaptureMonitor();
		if (recorderStarted) {
			boolean finalized = safeClose(mediaRecorder, true);
			if (!finalized || (bluetoothRecordingActive && !bluetoothCaptureVerified)) {
				deleteInvalidRecording();
			}
		} else if (isRecording && !voiceRouteStopped) {
			deleteInvalidRecording();
		}
		safeClose(mediaPlayerStart);
		safeClose(mediaPlayerStop);
		mediaRecorder = null;
		mediaPlayerStart = null;
		mediaPlayerStop = null;
		
		wayPointUuid = null;
		isRecording = false;
		recorderStarted = false;
		isStopping = false;
		bluetoothRecordingActive = false;
		bluetoothCaptureVerified = false;
		bluetoothRecordingDeviceId = -1;
		playSound = false;
		audioFile = null;
		audioSource = null;
		finishVoiceAudio();
		
		try {
			this.getOwnerActivity().setRequestedOrientation(currentRequestedOrientation);
		} catch(Exception e) {
			Log.w(TAG, "No OwnerActivity found for this Dialog. Use showDialog method within the activity to handle this Dialog and to avoid voice recording problems.");
		}
		
		super.onStop();
	}
	
	/* (non-Javadoc)
	 * @see android.app.AlertDialog#onKeyDown(int, android.view.KeyEvent)
	 */
	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (handleStopKey(event)) {
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (handleStopKey(event)) {
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	private boolean handleStopKey(KeyEvent event) {
		// only handle this event if it was raised after the dialog was shown
		if (event.getAction() != KeyEvent.ACTION_DOWN
				|| event.getRepeatCount() != 0
				|| event.getDownTime() <= dialogStartTime) {
			return false;
		}

		if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
				|| VoiceButtonPreferences.contains(
				PreferenceManager.getDefaultSharedPreferences(context), event.getKeyCode())) {
			stopRecording();
			return true;
		}

		return false;
	}

	public boolean isRecording() {
		return isRecording;
	}

	private boolean isCurrentRecording(long generation) {
		return isRecording && generation == recordingGeneration;
	}

	public void stopRecording() {
		if (isStopping) {
			return;
		}
		if (!recorderStarted) {
			discardRecording(false);
			return;
		}
		if (bluetoothRecordingActive && !bluetoothCaptureVerified) {
			discardRecording(false);
			return;
		}

		isStopping = true;
		cancelRecordingTimeout();

		if (VoiceRecordingSequence.stopsRecorderBeforeFinalBeep(bluetoothRecordingActive)) {
			stopPhoneRecording();
		} else {
			revalidateForFinalCue(false);
		}
	}

	/** Stops an active recording after a route, capture, or activity interruption. */
	public void interruptRecording() {
		if (!isRecording || isStopping) {
			return;
		}
		if (!recorderStarted || (bluetoothRecordingActive && !bluetoothCaptureVerified)) {
			failRecording();
			return;
		}

		isStopping = true;
		cancelRecordingTimeout();
		if (recorderStarted && bluetoothRecordingActive && voiceAudioRouter.isBluetoothActive()) {
			revalidateForFinalCue(true);
		} else {
			stopRecorder(true);
		}
	}

	/** Immediately finalizes an active recording when its activity is leaving the screen. */
	public void interruptRecordingImmediately() {
		if (!isRecording) {
			return;
		}

		isStopping = true;
		cancelRecordingTimeout();
		if (voiceRouteStopped) {
			VoiceRecDialog.this.dismiss();
		} else if (recorderStarted && (!bluetoothRecordingActive || bluetoothCaptureVerified)) {
			stopRecorder(true);
		} else {
			discardRecording(false);
		}
	}

	private void stopPhoneRecording() {
		long generation = recordingGeneration;
		boolean finalized = safeClose(mediaRecorder, recorderStarted);
		mediaRecorder = null;
		recorderStarted = false;
		if (!finalized) {
			deleteInvalidRecording();
			Toast.makeText(context, R.string.error_voicerec_failed, Toast.LENGTH_SHORT).show();
			completePhoneStop(generation);
			return;
		}
		finishVoiceAudio();

		if (mediaPlayerStop != null) {
			mediaPlayerStop.setOnCompletionListener(mp -> completePhoneStop(generation));
			mediaPlayerStop.setOnErrorListener((mp, what, extra) -> {
				completePhoneStop(generation);
				return true;
			});
			try {
				mediaPlayerStop.start();
				cancelFinalBeepStopFallback();
				finalBeepStopFallback = () -> {
					if (!isCurrentRecording(generation)) {
						return;
					}
					finalBeepStopFallback = null;
					completePhoneStop(generation);
				};
				handler.postDelayed(finalBeepStopFallback,
						Math.max(0, mediaPlayerStop.getDuration()) + FINAL_BEEP_STOP_BUFFER_MS);
				return;
			} catch (Exception e) {
				Log.w(TAG, "Failed to play stop sound", e);
			}
		}

		completePhoneStop(generation);
	}

	private void completePhoneStop(long generation) {
		if (!isCurrentRecording(generation)) {
			return;
		}
		cancelFinalBeepStopFallback();
		finishVoiceAudio();
		VoiceRecDialog.this.dismiss();
	}

	private void revalidateForFinalCue(boolean interrupted) {
		if (!recorderStarted) {
			stopRecorder(interrupted);
			return;
		}
		long generation = recordingGeneration;

		voiceAudioRouter.revalidateForCue(audioSource, new VoiceAudioRouter.Callback() {
			@Override
			public void onReady(boolean bluetoothActive) {
				if (!isCurrentRecording(generation)) {
					return;
				}
				if (bluetoothActive && recorderStarted) {
					playFinalCue(interrupted);
				} else {
					stopRecorder(interrupted);
				}
			}

			@Override
			public void onFailed() {
				if (isCurrentRecording(generation)) {
					stopRecorder(interrupted);
				}
			}
		});
	}

	private void playFinalCue(boolean interrupted) {
		if (mediaPlayerStop == null) {
			stopRecorder(interrupted);
			return;
		}
		long generation = recordingGeneration;

		mediaPlayerStop.setOnCompletionListener(mp -> {
			if (!isCurrentRecording(generation)) {
				return;
			}
			cancelFinalBeepStopFallback();
			if (interrupted) {
				playInterruptionAcknowledgement();
			} else {
				stopRecorderAfterFinalBeep(false, 0);
			}
		});
		mediaPlayerStop.setOnErrorListener((mp, what, extra) -> {
			if (!isCurrentRecording(generation)) {
				return true;
			}
			cancelFinalBeepStopFallback();
			stopRecorder(interrupted);
			return true;
		});
		try {
			mediaPlayerStop.start();
			scheduleFinalBeepStopFallback(interrupted);
		} catch (Exception e) {
			Log.w(TAG, "Failed to play stop sound", e);
			stopRecorder(interrupted);
		}
	}

	private void playInterruptionAcknowledgement() {
		ToneGenerator toneGenerator;
		try {
			toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, finalBeepVolume);
			toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150);
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to play interruption acknowledgement", e);
			stopRecorderAfterFinalBeep(true, 0);
			return;
		}
		handler.postDelayed(() -> safeClose(toneGenerator), 150);
		stopRecorderAfterFinalBeep(true, 150);
	}

	private void stopRecorderAfterFinalBeep(boolean interrupted, int acknowledgementDurationMs) {
		long generation = recordingGeneration;
		if (finalBeepDelayMs <= 0) {
			if (acknowledgementDurationMs <= 0) {
				stopRecorder(interrupted);
			} else {
				handler.postDelayed(() -> {
					if (isCurrentRecording(generation)) {
						stopRecorder(interrupted);
					}
				}, acknowledgementDurationMs);
			}
			return;
		}

		cancelStopRecorderAfterFinalBeep();
		stopRecorderAfterFinalBeep = () -> {
			if (!isCurrentRecording(generation)) {
				return;
			}
			stopRecorderAfterFinalBeep = null;
			stopRecorder(interrupted);
		};
		handler.postDelayed(stopRecorderAfterFinalBeep,
				finalBeepDelayMs + acknowledgementDurationMs);
	}

	private void stopRecorder(boolean interrupted) {
		cancelStopRecorderAfterFinalBeep();
		cancelFinalBeepStopFallback();
		cancelBluetoothRouteVerification();
		unregisterCaptureMonitor();
		boolean finalized = safeClose(mediaRecorder, recorderStarted);
		mediaRecorder = null;
		recorderStarted = false;
		if (!finalized) {
			deleteInvalidRecording();
		} else if (interrupted) {
			Toast.makeText(context, R.string.tracklogger_voicerec_interrupted, Toast.LENGTH_SHORT).show();
		}
		finishVoiceAudio();
		VoiceRecDialog.this.dismiss();
	}

	private void prepareMediaRecorder(
			File audioFile, boolean bluetoothActive, long bluetoothRouteDeadline) {
		if (!isRecording || isStopping) {
			return;
		}

		bluetoothRecordingActive = bluetoothActive;
		bluetoothCaptureVerified = !bluetoothActive;
		mediaRecorder = new MediaRecorder();
		try {
			prepareMediaPlayers(bluetoothActive);
			// MediaRecorder configuration
			mediaRecorder.setAudioSource(bluetoothActive
					? MediaRecorder.AudioSource.VOICE_COMMUNICATION
					: MediaRecorder.AudioSource.MIC);
			mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
			mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
			mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
			mediaRecorder.setMaxDuration(getMaxRecorderDuration(bluetoothActive));
			mediaRecorder.setOnInfoListener(this);
			mediaRecorder.setOnErrorListener(this::onRecorderError);
			if (bluetoothActive) {
				voiceAudioRouter.setRouteListener(this::interruptRecording);
				registerCaptureMonitor();
			}

			Log.d(TAG, "onStart() preparing mediaRecorder...");
			mediaRecorder.prepare();
			if (VoiceRecordingSequence.startsRecorderBeforeStartBeep(bluetoothActive)) {
				if (startMediaRecorder(audioFile)) {
					verifyBluetoothRouteBeforeStartCue(bluetoothRouteDeadline);
				}
			} else {
				playStartSound(() -> startMediaRecorder(audioFile));
			}
		} catch (Exception ioe) {
			Log.w(TAG, "onStart() voice recording has failed", ioe);
			failRecording();
		}
	}

	private int getMaxRecorderDuration(boolean bluetoothActive) {
		int maxDuration = recordingDuration * 1000;
		if (bluetoothActive && mediaPlayerStop != null) {
			maxDuration += getFinalBeepStopDelayMs();
		}
		return maxDuration;
	}

	private boolean startMediaRecorder(File audioFile) {
		if (!isRecording || isStopping || mediaRecorder == null) {
			return false;
		}

		try {
			Log.d(TAG, "onStart() starting mediaRecorder...");
			mediaRecorder.start();
			recorderStarted = true;
			Log.d(TAG,"onStart() mediaRecorder started...");

			Intent intent = new Intent(OSMTracker.INTENT_UPDATE_WP);
			intent.putExtra(Schema.COL_TRACK_ID, wayPointTrackId);
			intent.putExtra(OSMTracker.INTENT_KEY_UUID, wayPointUuid);
			intent.putExtra(OSMTracker.INTENT_KEY_LINK, audioFile.getName());
			intent.setPackage(getContext().getPackageName());
			context.sendBroadcast(intent);
			scheduleRecordingTimeout();
			return true;
		} catch (Exception e) {
			Log.w(TAG, "onStart() voice recording has failed", e);
			failRecording();
			return false;
		}
	}

	private void verifyBluetoothRouteBeforeStartCue(long deadline) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			bluetoothCaptureVerified = true;
			revalidateForStartCue();
			return;
		}

		long generation = recordingGeneration;
		bluetoothRouteVerification = new Runnable() {
			@Override
			public void run() {
				if (!isCurrentRecording(generation)
						|| !recorderStarted || mediaRecorder == null || isStopping) {
					return;
				}
				try {
					AudioDeviceInfo device = mediaRecorder.getRoutedDevice();
					if (isBluetoothDevice(device)) {
						bluetoothCaptureVerified = true;
						bluetoothRecordingDeviceId = device.getId();
						bluetoothRouteVerification = null;
						revalidateForStartCue();
						return;
					}
				} catch (RuntimeException e) {
					Log.w(TAG, "Could not verify the Bluetooth recording route", e);
				}
				if (SystemClock.uptimeMillis() >= deadline) {
					bluetoothRouteVerification = null;
					failRecording();
					return;
				}
				handler.postDelayed(this, 50);
			}
		};
		handler.post(bluetoothRouteVerification);
	}

	private void revalidateForStartCue() {
		long generation = recordingGeneration;
		voiceAudioRouter.revalidateForCue(audioSource, new VoiceAudioRouter.Callback() {
			@Override
			public void onReady(boolean bluetoothActive) {
				if (isCurrentRecording(generation)
						&& bluetoothActive && recorderStarted && !isStopping) {
					playStartSound(null);
				}
			}

			@Override
			public void onFailed() {
				// Recording continues without a cue when Bluetooth output cannot be confirmed.
			}
		});
	}

	private boolean isBluetoothDevice(AudioDeviceInfo device) {
		if (device == null) {
			return false;
		}
		int type = device.getType();
		return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
				|| (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				&& type == AudioDeviceInfo.TYPE_BLE_HEADSET);
	}

	private void playStartSound(Runnable afterSound) {
		MediaPlayer player = mediaPlayerStart;
		if (player == null) {
			if (afterSound != null) {
				afterSound.run();
			}
			return;
		}
		long generation = recordingGeneration;

		player.setOnCompletionListener(mp -> {
			safeClose(mp);
			if (mediaPlayerStart == mp) {
				mediaPlayerStart = null;
			}
			if (isCurrentRecording(generation) && afterSound != null) {
				afterSound.run();
			}
		});
		player.setOnErrorListener((mp, what, extra) -> {
			safeClose(mp);
			if (mediaPlayerStart == mp) {
				mediaPlayerStart = null;
			}
			if (isCurrentRecording(generation) && afterSound != null) {
				afterSound.run();
			}
			return true;
		});
		try {
			player.start();
		} catch (Exception e) {
			Log.w(TAG, "Failed to play start sound", e);
			safeClose(player);
			if (mediaPlayerStart == player) {
				mediaPlayerStart = null;
			}
			if (isCurrentRecording(generation) && afterSound != null) {
				afterSound.run();
			}
		}
	}

	private void scheduleRecordingTimeout() {
		cancelRecordingTimeout();
		if (!bluetoothRecordingActive) {
			return;
		}
		long generation = recordingGeneration;

		recordingTimeout = () -> {
			if (!isCurrentRecording(generation)) {
				return;
			}
			recordingTimeout = null;
			stopRecording();
		};
		handler.postDelayed(recordingTimeout, recordingDuration * 1000L);
	}

	private void cancelRecordingTimeout() {
		if (recordingTimeout == null) {
			return;
		}
		handler.removeCallbacks(recordingTimeout);
		recordingTimeout = null;
	}

	private void cancelStopRecorderAfterFinalBeep() {
		if (stopRecorderAfterFinalBeep == null) {
			return;
		}
		handler.removeCallbacks(stopRecorderAfterFinalBeep);
		stopRecorderAfterFinalBeep = null;
	}

	private void scheduleFinalBeepStopFallback(boolean interrupted) {
		cancelFinalBeepStopFallback();
		if (mediaPlayerStop == null) {
			return;
		}
		long generation = recordingGeneration;

		finalBeepStopFallback = () -> {
			if (!isCurrentRecording(generation)) {
				return;
			}
			finalBeepStopFallback = null;
			stopRecorder(interrupted);
		};
		handler.postDelayed(finalBeepStopFallback, getFinalBeepStopDelayMs());
	}

	private int getFinalBeepStopDelayMs() {
		int finalBeepDurationMs = mediaPlayerStop == null
				? 0
				: Math.max(0, mediaPlayerStop.getDuration());
		return VoiceRecordingSequence.getFinalBeepStopDelayMs(
				finalBeepDurationMs, finalBeepDelayMs, FINAL_BEEP_STOP_BUFFER_MS);
	}

	private void cancelFinalBeepStopFallback() {
		if (finalBeepStopFallback == null) {
			return;
		}
		handler.removeCallbacks(finalBeepStopFallback);
		finalBeepStopFallback = null;
	}

	private void cancelBluetoothRouteVerification() {
		if (bluetoothRouteVerification != null) {
			handler.removeCallbacks(bluetoothRouteVerification);
			bluetoothRouteVerification = null;
		}
	}

	private void registerCaptureMonitor() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
				|| recordingCallback != null
				|| mediaRecorder == null) {
			return;
		}
		monitoredRecorder = mediaRecorder;
		long generation = recordingGeneration;
		recordingCallback = new AudioManager.AudioRecordingCallback() {
			@Override
			public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
				if (!isCurrentRecording(generation)
						|| !recorderStarted || isStopping || !bluetoothCaptureVerified
						|| monitoredRecorder == null) {
					return;
				}
				try {
					AudioRecordingConfiguration configuration =
							monitoredRecorder.getActiveRecordingConfiguration();
					AudioDeviceInfo device = configuration == null
							? null : configuration.getAudioDevice();
					if (configuration == null || configuration.isClientSilenced()
							|| !isBluetoothDevice(device)
							|| (bluetoothRecordingDeviceId != -1
							&& device.getId() != bluetoothRecordingDeviceId)) {
						interruptRecording();
					}
				} catch (RuntimeException e) {
					Log.w(TAG, "Could not inspect the active voice recording", e);
				}
			}
		};
		try {
			monitoredRecorder.registerAudioRecordingCallback(handler::post, recordingCallback);
		} catch (RuntimeException e) {
			Log.w(TAG, "Could not monitor the active voice recording", e);
			recordingCallback = null;
			monitoredRecorder = null;
		}
	}

	private void unregisterCaptureMonitor() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || recordingCallback == null) {
			return;
		}
		try {
			if (monitoredRecorder != null) {
				monitoredRecorder.unregisterAudioRecordingCallback(recordingCallback);
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "Could not stop monitoring the voice recording", e);
		}
		recordingCallback = null;
		monitoredRecorder = null;
	}

	private void failRecording() {
		discardRecording(true);
	}

	private void discardRecording(boolean showError) {
		isStopping = true;
		if (showError) {
			Toast.makeText(context, R.string.error_voicerec_failed, Toast.LENGTH_SHORT).show();
		}
		cancelRecordingTimeout();
		cancelStopRecorderAfterFinalBeep();
		cancelFinalBeepStopFallback();
		cancelBluetoothRouteVerification();
		unregisterCaptureMonitor();
		safeClose(mediaRecorder, recorderStarted);
		safeClose(mediaPlayerStart);
		safeClose(mediaPlayerStop);
		mediaRecorder = null;
		recorderStarted = false;
		mediaPlayerStart = null;
		mediaPlayerStop = null;
		deleteInvalidRecording();
		finishVoiceAudio();
		VoiceRecDialog.this.dismiss();
	}

	private void deleteInvalidRecording() {
		if (audioFile != null && audioFile.exists() && !audioFile.delete()) {
			Log.w(TAG, "Could not delete invalid voice recording " + audioFile.getAbsolutePath());
		}
		if (wayPointUuid != null) {
			Intent intent = new Intent(OSMTracker.INTENT_DELETE_WP);
			intent.putExtra(OSMTracker.INTENT_KEY_UUID, wayPointUuid);
			intent.setPackage(getContext().getPackageName());
			context.sendBroadcast(intent);
		}
	}

	private void finishVoiceAudio() {
		if (voiceRouteStopped) {
			return;
		}

		voiceRouteStopped = true;
		voiceAudioRouter.setRouteListener(null);
		voiceAudioRouter.finishRecording();
	}

	private void prepareMediaPlayers(boolean bluetoothActive) {
		if (!playSound) {
			return;
		}

		mediaPlayerStart = createSoundPlayer(R.raw.beepbeep, bluetoothActive, startBeepVolume);
		mediaPlayerStop = createSoundPlayer(R.raw.beep, bluetoothActive, finalBeepVolume);
	}

	private MediaPlayer createSoundPlayer(int resId, boolean bluetoothActive, int volume) {
		MediaPlayer mediaPlayer = new MediaPlayer();
		AssetFileDescriptor afd = null;
		try {
			afd = context.getResources().openRawResourceFd(resId);
			mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
					.setUsage(bluetoothActive ? AudioAttributes.USAGE_VOICE_COMMUNICATION
							: AudioAttributes.USAGE_MEDIA)
					.setContentType(bluetoothActive ? AudioAttributes.CONTENT_TYPE_SPEECH
							: AudioAttributes.CONTENT_TYPE_MUSIC)
					.build());
			if (bluetoothActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
				AudioDeviceInfo device = audioManager.getCommunicationDevice();
				if (!isBluetoothDevice(device) || !mediaPlayer.setPreferredDevice(device)) {
					throw new IllegalStateException("Bluetooth cue output is not available");
				}
			}
			mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
			mediaPlayer.setLooping(false);
			float relativeVolume = volume / 100f;
			mediaPlayer.setVolume(relativeVolume, relativeVolume);
			mediaPlayer.prepare();
			return mediaPlayer;
		} catch (Exception e) {
			Log.w(TAG, "Failed to prepare sound player", e);
			safeClose(mediaPlayer);
			return null;
		} finally {
			if (afd != null) {
				try {
					afd.close();
				} catch (Exception ignored) {
					// Nothing useful to do here.
				}
			}
		}
	}

	/**
	 * @return a new File in the current track directory.
	 */
	public File getAudioFile() {
		File audioFile = null;
		
		// Query for current track directory
		File trackDir = DataHelper.getTrackDirectory(wayPointTrackId, context);
		
		// Create the track storage directory if it does not yet exist
		if (!trackDir.exists()) {
			if ( !trackDir.mkdirs() ) {
				Log.w(TAG, "Directory [" + trackDir.getAbsolutePath() + "] does not exist and cannot be created");
			}
		}

		// Ensure that this location can be written to 
		if (trackDir.exists() && trackDir.canWrite()) {
			String baseName = DataHelper.FILENAME_FORMATTER.format(new Date());
			for (int suffix = 0; ; suffix++) {
				String suffixText = suffix == 0 ? "" : "_" + suffix;
				audioFile = new File(trackDir,
						baseName + suffixText + DataHelper.EXTENSION_3GPP);
				try {
					if (audioFile.createNewFile()) {
						break;
					}
				} catch (IOException e) {
					Log.w(TAG, "Could not reserve voice recording file", e);
					return null;
				}
			}
		} else {
			Log.w(TAG, "The directory [" + trackDir.getAbsolutePath() + "] will not allow files to be created");
		}
		
		return audioFile;
	}

	/**
	 * Safely close a {@link MediaPlayer} without throwing
	 * exceptions
	 * @param mp
	 */
	private void safeClose(MediaPlayer mp) {
		if (mp != null) {
			try {
				mp.stop();
			} catch (Exception e) {
				Log.w(TAG, "Failed to stop media player",e);
			} finally {
				try {
					mp.release();
				} catch (RuntimeException e) {
					Log.w(TAG, "Failed to release media player", e);
				}
			}
		}
	}

	private void safeClose(ToneGenerator toneGenerator) {
		try {
			toneGenerator.release();
		} catch (RuntimeException e) {
			Log.w(TAG, "Failed to release tone generator", e);
		}
	}
	
	/**
	 * Safely close a {@link MediaRecorder} without throwing
	 * exceptions
	 * @param mr
	 */
	private boolean safeClose(MediaRecorder mr, boolean stopIt) {
		boolean stopped = !stopIt;
		if (mr != null) {
			try {
				if (stopIt) {
					mr.stop();
					stopped = true;
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to stop media recorder",e);
			} finally {
				try {
					mr.release();
				} catch (RuntimeException e) {
					Log.w(TAG, "Failed to release media recorder", e);
				}
			}
		}
		return stopped;
	}


}
