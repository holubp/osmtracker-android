package net.osmtracker.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public class MediaButtonReceiver extends BroadcastReceiver {

	public interface MediaButtonListener {
		boolean onMediaButton(KeyEvent event);
	}

	private static MediaButtonListener activeListener;
	private static MediaButtonListener captureListener;

	public static void setActiveListener(MediaButtonListener listener) {
		activeListener = listener;
	}

	public static void setCaptureListener(MediaButtonListener listener) {
		captureListener = listener;
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
			return;
		}

		KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
		if (event == null) {
			return;
		}

		if (captureListener != null && captureListener.onMediaButton(event)) {
			return;
		}

		if (activeListener != null) {
			activeListener.onMediaButton(event);
		}
	}

}
