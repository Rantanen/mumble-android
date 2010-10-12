package org.pcgod.mumbleclient.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.pcgod.mumbleclient.Globals;
import org.pcgod.mumbleclient.service.AudioUser.PacketReadyHandler;
import org.pcgod.mumbleclient.service.model.User;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Audio output thread.
 * Handles the playback of UDP packets added with addFrameToBuffer.
 *
 * @author pcgod, Rantanen
 */
class AudioOutput implements Runnable {

	private final PacketReadyHandler packetReadyHandler = new PacketReadyHandler() {

		@Override
		public void packetReady(AudioUser user) {
			synchronized (userPackets) {
				if (!userPackets.containsKey(user.getUser())) {
					host.setTalkState(
						user.getUser(),
						AudioOutputHost.STATE_TALKING);
					userPackets.put(user.getUser(), user);
					userPackets.notify();
				}
			}
		}
	};

	private final static int standbyTreshold = 2000;

	private boolean shouldRun;
	private final AudioTrack at;
	private final int bufferSize;
	private final int minBufferSize;

	private final Map<User, AudioUser> userPackets = new HashMap<User, AudioUser>();
	private final Map<User, AudioUser> users = new HashMap<User, AudioUser>();

	/**
	 * Buffer used to hold temporary float values while mixing multiple
	 * inputs. Only for use in the audio thread.
	 */
	final float[] tempMix = new float[MumbleConnection.FRAME_SIZE];

	private final AudioOutputHost host;

	public AudioOutput(AudioOutputHost host) {
		this.host = host;

		minBufferSize = AudioTrack.getMinBufferSize(
			MumbleConnection.SAMPLE_RATE,
			AudioFormat.CHANNEL_CONFIGURATION_MONO,
			AudioFormat.ENCODING_PCM_16BIT);

		// Double the buffer size to reduce stuttering.
		final int desiredBufferSize = minBufferSize * 2;

		// Resolve the minimum frame count that fills the minBuffer requirement.
		final int frameCount = (int) Math.ceil((double) desiredBufferSize /
												MumbleConnection.FRAME_SIZE);

		bufferSize = frameCount * MumbleConnection.FRAME_SIZE;

		at = new AudioTrack(
			AudioManager.STREAM_MUSIC,
			MumbleConnection.SAMPLE_RATE,
			AudioFormat.CHANNEL_CONFIGURATION_MONO,
			AudioFormat.ENCODING_PCM_16BIT,
			bufferSize,
			AudioTrack.MODE_STREAM);

		// Set this here so this.start(); this.shouldRun = false; doesn't
		// result in run() setting shouldRun to true afterwards and continuing
		// running.
		shouldRun = true;
	}

	public void addFrameToBuffer(
		final User u,
		final PacketDataStream pds,
		final int flags) {

		AudioUser user;

		user = users.get(u);
		if (user == null) {
			user = new AudioUser(u);
			users.put(u, user);
			// Don't add the user to userPackets yet. The collection should
			// have only users with ready frames. Since this method is
			// called only from the TCP connection thread it will never
			// create a new AudioUser while a previous one is still decoding.
		}

		user.addFrameToBuffer(pds, packetReadyHandler);
	}

	private void audioLoop() throws InterruptedException {
		final short[] out = new short[MumbleConnection.FRAME_SIZE];
		final List<AudioUser> mix = new LinkedList<AudioUser>();

		int buffered = 0;
		boolean playing = false;

		while (shouldRun) {
			mix.clear();

			// Get mix frames from the AudioUsers
			fillMixFrames(mix);

			// If there is output, play it now.
			if (mix.size() > 0) {

				// Mix all the frames into one array.
				mix(out, mix);

				at.write(out, 0, MumbleConnection.FRAME_SIZE);

				// Make sure we are playing when there are enough samples
				// buffered.
				if (!playing) {
					buffered += out.length;

					if (buffered >= minBufferSize) {
						at.play();
						playing = true;
						buffered = 0;

						Log.i(Globals.LOG_TAG, "AudioOutput: Enough data buffered. Starting audio.");
					}
				}

				// Continue with playback since we know that there is at least
				// one AudioUser in userPackets that wasn't removed as it had
				// frames for mixing.
				continue;
			}

			// Wait for more input.
			playing &= !pauseForInput();
			if (!playing && buffered > 0) {
				Log.w(
					Globals.LOG_TAG,
					"AudioOutput: Stopped playing while buffered data present.");
			}
		}
		at.flush();
		at.stop();
	}

	private boolean pauseForInput() throws InterruptedException {
		long silentTime;
		boolean paused = false;
		synchronized (userPackets) {
			silentTime = System.currentTimeMillis();

			// Wait with the audio on
			while (shouldRun &&
					userPackets.isEmpty() &&
					(silentTime + standbyTreshold) > System.currentTimeMillis()) {

				userPackets.wait((silentTime + standbyTreshold)
									- System.currentTimeMillis() + 1);
			}

			// If conditions are still not filled, pause audio and wait more.
			if (shouldRun && userPackets.isEmpty()) {
				at.pause();
				paused = true;
				Log.i(Globals.LOG_TAG, "AudioOutput: Standby timeout reached. Audio paused.");

				while (shouldRun && userPackets.isEmpty()) {
					userPackets.wait();
				}
			}
		}
		return paused;
	}

	private void mix(final short[] clipOut, final List<AudioUser> mix) {
		// Reset mix buffer.
		Arrays.fill(tempMix, 0);

		// Sum the buffers.
		for (final AudioUser user : mix) {
			for (int i = 0; i < tempMix.length; i++) {
				tempMix[i] += user.lastFrame[i];
			}
		}

		// Clip buffer for real output.
		for (int i = 0; i < MumbleConnection.FRAME_SIZE; i++) {
			clipOut[i] = (short) (Short.MAX_VALUE * (tempMix[i] < -1.0f ? -1.0f
				: (tempMix[i] > 1.0f ? 1.0f : tempMix[i])));
		}
	}

	private void fillMixFrames(final List<AudioUser> mix) {
		synchronized (userPackets) {
			final Iterator<AudioUser> i = userPackets.values().iterator();
			while (i.hasNext()) {
				final AudioUser user = i.next();
				if (user.hasFrame()) {
					mix.add(user);
				} else {
					i.remove();
					host.setTalkState(
						user.getUser(),
						AudioOutputHost.STATE_PASSIVE);
				}
			}
		}
	}

	public void run() {
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
		try {
			audioLoop();
		} catch (final InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void stop() {
		shouldRun = false;
		synchronized (userPackets) {
			userPackets.notify();
		}
	}
}
