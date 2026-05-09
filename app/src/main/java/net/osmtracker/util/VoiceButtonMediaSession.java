package net.osmtracker.util;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

import net.osmtracker.receiver.MediaButtonReceiver;

public class VoiceButtonMediaSession {

	private final MediaSession mediaSession;

	public VoiceButtonMediaSession(Context context, String tag,
								   MediaButtonReceiver.MediaButtonListener listener) {
		mediaSession = new MediaSession(context.getApplicationContext(), tag);
		mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
				| MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
		mediaSession.setCallback(new MediaSession.Callback() {
			@Override
			public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
				KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
				return event != null && listener.onMediaButton(event);
			}

			@Override
			public void onPlay() {
				handleTransportKey(KeyEvent.KEYCODE_MEDIA_PLAY);
			}

			@Override
			public void onPause() {
				handleTransportKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
			}

			@Override
			public void onSkipToNext() {
				handleTransportKey(KeyEvent.KEYCODE_MEDIA_NEXT);
			}

			@Override
			public void onSkipToPrevious() {
				handleTransportKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
			}

			@Override
			public void onStop() {
				handleTransportKey(KeyEvent.KEYCODE_MEDIA_STOP);
			}

			private void handleTransportKey(int keyCode) {
				listener.onMediaButton(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
				refreshPlaybackState();
			}
		});
		refreshPlaybackState();
	}

	private void refreshPlaybackState() {
		mediaSession.setPlaybackState(new PlaybackState.Builder()
				.setActions(PlaybackState.ACTION_PLAY
						| PlaybackState.ACTION_PAUSE
						| PlaybackState.ACTION_PLAY_PAUSE
						| PlaybackState.ACTION_SKIP_TO_NEXT
						| PlaybackState.ACTION_SKIP_TO_PREVIOUS
						| PlaybackState.ACTION_STOP)
				.setState(PlaybackState.STATE_PLAYING, 0, 1)
				.build());
	}

	public void start() {
		mediaSession.setActive(true);
	}

	public void stop() {
		mediaSession.setActive(false);
		mediaSession.release();
	}
}
