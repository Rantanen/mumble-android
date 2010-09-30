package org.pcgod.mumbleclient.service;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;

import org.pcgod.mumbleclient.jni.celt;
import org.pcgod.mumbleclient.service.model.User;


class AudioUser {
	JitterBuffer jb;
	User u;
	short[] pfBuffer;
	private int bufferFilled;
	private boolean lastAlive = true;
	private final LinkedList<short[]> frameList = new LinkedList<short[]>();
	private int missCount;
	private int ucFlags = 0xFF;
	private boolean hasTerminator;
	private short[] pOut = new short[MumbleClient.FRAME_SIZE];
	private static double[] fadeIn;
	private static double[] fadeOut;

	static {
		fadeIn = new double[MumbleClient.FRAME_SIZE];
		fadeOut = new double[MumbleClient.FRAME_SIZE];
		final double mul = Math.PI / (2.0 * MumbleClient.FRAME_SIZE);
		for (int i = 0; i < MumbleClient.FRAME_SIZE; ++i) {
			fadeIn[i] = fadeOut[MumbleClient.FRAME_SIZE - i - 1] = Math.sin(i
					* mul);
		}
	}

	AudioUser(final User u_) {
		u = u_;
		jb = new JitterBuffer(MumbleClient.FRAME_SIZE);
//		jb.setMargin(50 * MumbleClient.FRAME_SIZE);
	}

	void addFrameToBuffer(final ByteBuffer packet, final int iSeq, int flags) {
		final PacketDataStream pds = new PacketDataStream(packet);

		int frames = 0;
		int header = 0;
		do {
			header = pds.next();
			++frames;
			pds.skip(header & 0x7f);
		} while (((header & 0x80) > 0) && pds.isValid());

		if (pds.isValid()) {
			packet.rewind();
			final JitterBufferPacket jbp = new JitterBufferPacket();
			jbp.flags = flags;
			jbp.data = packet;
			jbp.span = MumbleClient.FRAME_SIZE * frames;
			jbp.timestamp = MumbleClient.FRAME_SIZE * iSeq;

			synchronized (jb) {
				jb.put(jbp);
			}
		}
	}

	boolean needSamples(final int snum) {
		if (pfBuffer == null) {
			pfBuffer = new short[snum];
		}

		bufferFilled = 0;
		boolean nextAlive = lastAlive;

		while (bufferFilled < snum) {
			if (!lastAlive) {
				Arrays.fill(pfBuffer, bufferFilled, bufferFilled + MumbleClient.FRAME_SIZE, (short) 0);
			} else {
				final int available;
				final int timestamp;
				synchronized (jb) {
					available = jb.getAvailable();
					timestamp = jb.getTimestamp();
				}

				if (u != null && timestamp == 0) {
					final int want = (int) u.averageAvailable;
					if (available < want) {
						++missCount;
						if (missCount < 20) {
							Arrays.fill(pfBuffer, bufferFilled, bufferFilled + MumbleClient.FRAME_SIZE, (short) 0);
							bufferFilled += MumbleClient.FRAME_SIZE;
							continue;
						}
					}
				}

				if (frameList.isEmpty()) {
					final JitterBufferPacket jbp;
					synchronized (jb) {
						jbp = jb.get(MumbleClient.FRAME_SIZE);
					}
					if (jbp != null) {
						final PacketDataStream pds = new PacketDataStream(
								jbp.data);

						missCount = 0;
						ucFlags = jbp.flags;
						hasTerminator = false;

						int header = 0;
						do {
							header = pds.next();
							if (header > 0) {
								frameList.add(pds.dataBlock(header & 0x7f));
							} else {
								hasTerminator = true;
							}
						} while (((header & 0x80) > 0) && pds.isValid());

//						Log.i("mumbleclient", "frames: " + frames + " valid: "
//								+ pds.isValid());

//						if (pds.left() > 0) {
//							final float x = pds.readFloat();
//							final float y = pds.readFloat();
//							final float z = pds.readFloat();
//							if (ANDROID) {
//								Log.i(LOG_TAG, "x: " + x + " y: " + y + " z: " + z);
//							} else {
//								System.out.println("x: " + x + " y: " + y + " z: " + z);
//							}
//						}

						if (u != null) {
							final float a = available;
							if (available >= u.averageAvailable) {
								u.averageAvailable = a;
							} else {
								u.averageAvailable *= 0.99;
							}
						}
					} else {
						synchronized (jb) {
							jb.updateDelay();
						}

						++missCount;
						if (missCount > 10) {
							nextAlive = false;
						}
					}
				}

				if (!frameList.isEmpty()) {
					final short[] frame = frameList.poll();

					synchronized (celt.class) {
						celt.celt_decode(AudioOutput.celtDecoder, frame,
								frame.length, pOut);
					}

					System.arraycopy(pOut, 0, pfBuffer, bufferFilled, MumbleClient.FRAME_SIZE);
					final boolean update = true;
//	                if (p) {
//	                    float &fPowerMax = p->fPowerMax;
//	                    float &fPowerMin = p->fPowerMin;
//
//	                    float pow = 0.0f;
//	                    for (unsigned int i=0;i<iFrameSize;++i)
//	                        pow += pOut[i] * pOut[i];
//	                    pow = sqrtf(pow / static_cast<float>(iFrameSize));
//
//	                    if (pow >= fPowerMax) {
//	                        fPowerMax = pow;
//	                    } else {
//	                        if (pow <= fPowerMin) {
//	                            fPowerMin = pow;
//	                        } else {
//	                            fPowerMax = 0.99f * fPowerMax;
//	                            fPowerMin += 0.0001f * pow;
//	                        }
//	                    }
//
//	                    update = (pow < (fPowerMin + 0.01f * (fPowerMax - fPowerMin)));
//	                }
					if (frameList.isEmpty() && update) {
						synchronized (jb) {
							jb.updateDelay();
						}
					}

					if (frameList.isEmpty() && hasTerminator) {
						nextAlive = false;
					}
				}

//				if (!nextAlive) {
//					for (int i = 0; i < MumbleClient.FRAME_SIZE; ++i) {
//						pOut[i] *= fadeOut[i];
//					}
//				} else if (timestamp == 0) {
//					for (int i = 0; i < MumbleClient.FRAME_SIZE; ++i) {
//						pOut[i] *= fadeIn[i];
//					}
//				}

				synchronized (jb) {
					jb.tick();
				}
			}
			bufferFilled += MumbleClient.FRAME_SIZE;
		}

		if (u != null) {
			if (!nextAlive) {
				ucFlags = 0xFF;
			}
			switch (ucFlags) {
			case 0:
				u.talkingState = User.TALKINGSTATE_TALKING;
				break;
			case 1:
				u.talkingState = User.TALKINGSTATE_SHOUTING;
				break;
			case 0xFF:
				u.talkingState = User.TALKINGSTATE_PASSIVE;
				break;
			default:
				u.talkingState = User.TALKINGSTATE_WHISPERING;
				break;
			}
		}

		final boolean tmp = lastAlive;
		lastAlive = nextAlive;
		return tmp;
	}
}
