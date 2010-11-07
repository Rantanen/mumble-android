package org.pcgod.mumbleclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.preference.PreferenceManager;

public class Settings {
	public static final String PREF_STREAM = "stream";
	public static final String ARRAY_STREAM_MUSIC = "music";
	public static final String ARRAY_STREAM_CALL = "call";

	public static final String PREF_JITTER = "buffering";
	public static final String ARRAY_JITTER_NONE = "none";
	public static final String ARRAY_JITTER_SPEEX = "speex";

	private final SharedPreferences preferences;
	public Settings(Context ctx) {
		preferences =
			PreferenceManager.getDefaultSharedPreferences(ctx);
	}

	public boolean isJitterBuffer() {
		return preferences.getString(
			PREF_JITTER,
			ARRAY_JITTER_NONE).equals(ARRAY_JITTER_SPEEX);
	}

	public int getAudioStream() {
		return preferences.getString(
			PREF_STREAM,
			ARRAY_STREAM_MUSIC).equals(ARRAY_STREAM_MUSIC)
				? AudioManager.STREAM_MUSIC
				: AudioManager.STREAM_VOICE_CALL;
	}
}