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
		mediaSession.setCallback(new MediaSession.Callback() {
			@Override
			public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
				KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
				return event != null && listener.onMediaButton(event);
			}
		});
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
