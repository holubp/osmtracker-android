package net.osmtracker.view;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnInfoListener;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import net.osmtracker.OSMTracker;
import net.osmtracker.R;
import net.osmtracker.db.DataHelper;
import net.osmtracker.db.TrackContentProvider.Schema;
import net.osmtracker.util.VoiceAudioRouter;
import net.osmtracker.util.VoiceButtonPreferences;

import java.io.File;
import java.util.Date;
import java.util.UUID;

public class VoiceRecDialog extends ProgressDialog implements OnInfoListener{
	
	private final static String TAG = VoiceRecDialog.class.getSimpleName();
	
	/**
	 * Id of the track the dialog will add this waypoint to
	 */
	private long wayPointTrackId;
	
	/**
	 * Unique identifier of the waypoint this dialog working on
	 */
	private String wayPointUuid = null;
	
	/**
	 * AudioManager, to unmute microphone
	 */
	private AudioManager audioManager;
	
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
	
	/**
	 * MediaPlayer used to play a short beepbeep when recording starts
	 */
	private MediaPlayer mediaPlayerStart = null;

	/**
	 * MediaPlayer used to play a short beep when recording stops
	 */
	private MediaPlayer mediaPlayerStop = null;

	private boolean playSound = false;
	
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
		
		// Try to un-mute microphone, just in case
		audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

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
			voiceRouteStopped = false;
			// Get a new audio filename
			File audioFile = getAudioFile();

			if (audioFile != null) {

				playSound = preferences.getBoolean(OSMTracker.Preferences.KEY_SOUND_ENABLED,
						OSMTracker.Preferences.VAL_SOUND_ENABLED);

				// Some workaround for record problems
				unMuteMicrophone();

				String audioSource = VoiceAudioRouter.getAudioSource(preferences);
				voiceAudioRouter.prepareForRecording(audioSource, new VoiceAudioRouter.Callback() {
					@Override
					public void onReady(boolean bluetoothActive) {
						prepareMediaRecorder(audioFile, bluetoothActive);
					}

					@Override
					public void onFailed() {
						failRecording();
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

		android.widget.Button stopButton = getButton(DialogInterface.BUTTON_NEGATIVE);
		if (stopButton != null) {
			stopButton.setOnClickListener(v -> stopRecording());
		}
	}
	
	@Override
	public void onInfo(MediaRecorder mr, int what, int extra) {
		Log.d(TAG, "onInfo() received mediaRecorder info ("+String.valueOf(what)+")");
		switch(what){
		case MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN:
		case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
		case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
			stopRecording();
			break;
		}
	}
	
	/**
	 * called when the dialog disappears
	 */
	@Override
	protected void onStop() {
		Log.d(TAG, "onStop() called");
		 
		safeClose(mediaRecorder, false);
		safeClose(mediaPlayerStart);
		safeClose(mediaPlayerStop);
		mediaRecorder = null;
		mediaPlayerStart = null;
		mediaPlayerStop = null;
		
		wayPointUuid = null;
		isRecording = false;
		recorderStarted = false;
		isStopping = false;
		playSound = false;
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

	public void stopRecording() {
		if (isStopping) {
			return;
		}

		isStopping = true;
		safeClose(mediaRecorder, recorderStarted);
		mediaRecorder = null;
		recorderStarted = false;

		if (mediaPlayerStop != null) {
			mediaPlayerStop.setOnCompletionListener(mp -> {
				finishVoiceAudio();
				VoiceRecDialog.this.dismiss();
			});
			try {
				mediaPlayerStop.start();
				return;
			} catch (Exception e) {
				Log.w(TAG, "Failed to play stop sound", e);
			}
		}

		finishVoiceAudio();
		VoiceRecDialog.this.dismiss();
	}

	private void prepareMediaRecorder(File audioFile, boolean bluetoothActive) {
		if (!isRecording || isStopping) {
			return;
		}

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
			mediaRecorder.setMaxDuration(recordingDuration * 1000);
			mediaRecorder.setOnInfoListener(this);

			Log.d(TAG, "onStart() preparing mediaRecorder...");
			mediaRecorder.prepare();
			playStartSound(() -> startMediaRecorder(audioFile));
		} catch (Exception ioe) {
			Log.w(TAG, "onStart() voice recording has failed", ioe);
			failRecording();
		}
	}

	private void startMediaRecorder(File audioFile) {
		if (!isRecording || isStopping || mediaRecorder == null) {
			return;
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
		} catch (Exception e) {
			Log.w(TAG, "onStart() voice recording has failed", e);
			failRecording();
		}
	}

	private void playStartSound(Runnable afterSound) {
		if (mediaPlayerStart == null) {
			afterSound.run();
			return;
		}

		mediaPlayerStart.setOnCompletionListener(mp -> {
			safeClose(mediaPlayerStart);
			mediaPlayerStart = null;
			afterSound.run();
		});
		try {
			mediaPlayerStart.start();
		} catch (Exception e) {
			Log.w(TAG, "Failed to play start sound", e);
			safeClose(mediaPlayerStart);
			mediaPlayerStart = null;
			afterSound.run();
		}
	}

	private void failRecording() {
		Toast.makeText(context, context.getResources().getString(R.string.error_voicerec_failed),
				Toast.LENGTH_SHORT).show();
		finishVoiceAudio();
		VoiceRecDialog.this.dismiss();
	}

	private void finishVoiceAudio() {
		if (voiceRouteStopped) {
			return;
		}

		voiceRouteStopped = true;
		voiceAudioRouter.finishRecording(PreferenceManager.getDefaultSharedPreferences(context));
	}

	private void prepareMediaPlayers(boolean bluetoothActive) {
		if (!playSound) {
			return;
		}

		mediaPlayerStart = createSoundPlayer(R.raw.beepbeep, bluetoothActive);
		mediaPlayerStop = createSoundPlayer(R.raw.beep, bluetoothActive);
	}

	private MediaPlayer createSoundPlayer(int resId, boolean bluetoothActive) {
		MediaPlayer mediaPlayer = new MediaPlayer();
		AssetFileDescriptor afd = null;
		try {
			afd = context.getResources().openRawResourceFd(resId);
			mediaPlayer.setAudioStreamType(bluetoothActive
					? AudioManager.STREAM_VOICE_CALL
					: AudioManager.STREAM_MUSIC);
			mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
			mediaPlayer.setLooping(false);
			if (resId == R.raw.beepbeep) {
				mediaPlayer.setVolume(0.6f, 0.6f);
			}
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
	 * Un-mute the microphone, to prevent a blank-recording
	 * on certain devices (Acer Liquid ?)
	 */
	private void unMuteMicrophone() {
		Log.v(TAG, "unMuteMicrophone()");
		if (audioManager.isMicrophoneMute()) {
			audioManager.setMicrophoneMute(false);
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
			audioFile = new File(trackDir, 
					DataHelper.FILENAME_FORMATTER.format(new Date()) + DataHelper.EXTENSION_3GPP);
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
				mp.release();
			}
		}
	}
	
	/**
	 * Safely close a {@link MediaRecorder} without throwing
	 * exceptions
	 * @param mr
	 */
	private void safeClose(MediaRecorder mr, boolean stopIt) {
		if (mr != null) {
			try {
				if (stopIt) {
					mr.stop();
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to stop media recorder",e);
			} finally {
				mr.release();
			}
		}
	}


}
