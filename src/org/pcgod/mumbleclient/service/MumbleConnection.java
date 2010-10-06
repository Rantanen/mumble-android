package org.pcgod.mumbleclient.service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import net.sf.mumble.MumbleProto.Authenticate;
import net.sf.mumble.MumbleProto.ChannelRemove;
import net.sf.mumble.MumbleProto.ChannelState;
import net.sf.mumble.MumbleProto.ServerSync;
import net.sf.mumble.MumbleProto.TextMessage;
import net.sf.mumble.MumbleProto.UserRemove;
import net.sf.mumble.MumbleProto.UserState;
import net.sf.mumble.MumbleProto.Version;

import org.pcgod.mumbleclient.Globals;
import org.pcgod.mumbleclient.service.MumbleConnectionHost.ConnectionState;
import org.pcgod.mumbleclient.service.model.Channel;
import org.pcgod.mumbleclient.service.model.Message;
import org.pcgod.mumbleclient.service.model.User;
import org.pcgod.mumbleclient.service.model.Message.Direction;

import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

/**
 * The main mumble client connection
 * 
 * Maintains connection to the server and implements the low level
 * communication protocol
 * 
 * @author pcgod
 *
 */
public class MumbleConnection implements Runnable {
	public enum MessageType {
		Version, UDPTunnel, Authenticate, Ping, Reject, ServerSync, ChannelRemove, ChannelState, UserRemove, UserState, BanList, TextMessage, PermissionDenied, ACL, QueryUsers, CryptSetup, ContextActionAdd, ContextAction, UserList, VoiceTarget, PermissionQuery, CodecVersion, UserStats, RequestBlob, ServerConfig
	}

	public static final int UDPMESSAGETYPE_UDPVOICECELTALPHA = 0;
	public static final int UDPMESSAGETYPE_UDPPING = 1;
	public static final int UDPMESSAGETYPE_UDPVOICESPEEX = 2;
	public static final int UDPMESSAGETYPE_UDPVOICECELTBETA = 3;

	public static final int SAMPLE_RATE = 48000;
	public static final int FRAME_SIZE = SAMPLE_RATE / 100;

	private static final MessageType[] MT_CONSTANTS = MessageType.class.getEnumConstants();

	private static final int protocolVersion = (1 << 16) | (2 << 8)
			| (3 & 0xFF);

	public ArrayList<Channel> channelArray = new ArrayList<Channel>();
	public int currentChannel = -1;
	public int session;
	public boolean canSpeak = true;
	public ArrayList<User> userArray = new ArrayList<User>();
	private final MumbleConnectionHost connectionHost;

	private DataInputStream in;
	private DataOutputStream out;
	private Thread pingThread;
	private boolean disconnecting = false; 

	private final String host;
	private final int port;
	private final String username;
	private final String password;

	private AudioOutput ao;
	private Thread audioOutputThread;
	private Thread readingThread;
	private Object stateLock = new Object();

	public MumbleConnection(final MumbleConnectionHost connectionHost_,
			final String host_, final int port_,
			final String username_, final String password_) {
		connectionHost = connectionHost_;
		host = host_;
		port = port_;
		username = username_;
		password = password_;
		
		connectionHost.setConnectionState(ConnectionState.Disconnected);
	}

	public final boolean isSameServer(final String host_, final int port_,
			final String username_, final String password_) {
		return host.equals(host_) && port == port_
				&& username.equals(username_) && password.equals(password_);
	}

	public final void joinChannel(final int channelId) {
		final UserState.Builder us = UserState.newBuilder();
		us.setSession(session);
		us.setChannelId(channelId);
		try {
			sendMessage(MessageType.UserState, us);
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	public final void run() {
		connectionHost.setConnectionState(ConnectionState.Connecting);
		
		try {
			SSLSocket socket_;
			
			synchronized(stateLock) {
				final SSLContext ctx_ = SSLContext.getInstance("TLS");
				ctx_.init(null, new TrustManager[] { new LocalSSLTrustManager() },
						null);
				final SSLSocketFactory factory = ctx_.getSocketFactory();
				socket_ = (SSLSocket) factory.createSocket(host,
						port);
				socket_.setUseClientMode(true);
				socket_.setEnabledProtocols(new String[] { "TLSv1" });
				socket_.startHandshake();
		
				connectionHost.setConnectionState(ConnectionState.Connected);
			}
			
			handleProtocol(socket_);
			
			socket_.close();
			
		} catch (final NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (final KeyManagementException e) {
			e.printStackTrace();
		} catch (final UnknownHostException e) {
			e.printStackTrace();
		} catch (final IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		synchronized(stateLock) {
			connectionHost.setConnectionState(ConnectionState.Disconnected);
		}
	}

	public final void sendChannelTextMessage(final String message) {
		final TextMessage.Builder tmb = TextMessage.newBuilder();
		tmb.addChannelId(currentChannel);
		tmb.setMessage(message);
		try {
			sendMessage(MessageType.TextMessage, tmb);
		} catch (final IOException e) {
			e.printStackTrace();
		}
		
		final Channel c = findChannel(currentChannel);
		
		Message msg = new Message();
		msg.timestamp = System.currentTimeMillis();
		msg.message = message;
		msg.channel = c;
		msg.direction = Direction.Sent;
		connectionHost.messageSent(msg);
	}

	public final void sendMessage(final MessageType t,
			final MessageLite.Builder b) throws IOException {
		final MessageLite m = b.build();
		final short type = (short) t.ordinal();
		final int length = m.getSerializedSize();

		synchronized (out) {
			out.writeShort(type);
			out.writeInt(length);
			m.writeTo(out);
		}

		if (t != MessageType.Ping) {
			Log.i(Globals.LOG_TAG, "<<< " + t);
		}
	}

	public final void sendUdpTunnelMessage(final byte[] buffer)
			throws IOException {
		final short type = (short) MessageType.UDPTunnel.ordinal();
		final int length = buffer.length;

		synchronized (out) {
			out.writeShort(type);
			out.writeInt(length);
			out.write(buffer);
		}
	}
	
	public final void disconnect() {
		synchronized (stateLock) {
			if (readingThread != null) {
				readingThread.interrupt();
				readingThread = null;
			}
			
			disconnecting = true;
			connectionHost.setConnectionState(ConnectionState.Disconnecting);
			stateLock.notifyAll();
		}
	}
	
	public final boolean isConnectionAlive() {
	    return !disconnecting;
	}

	private Channel findChannel(final int id) {
		for (final Channel c : channelArray) {
			if (c.id == id) {
				return c;
			}
		}

		return null;
	}

	private User findUser(final int session_) {
		for (final User u : userArray) {
			if (u.session == session_) {
				return u;
			}
		}

		return null;
	}

	private void handleProtocol(final Socket socket_) throws IOException, InterruptedException {
		
		synchronized (stateLock) {
			if (disconnecting) return;
			
			out = new DataOutputStream(socket_.getOutputStream());
			in = new DataInputStream(socket_.getInputStream());
	
			final Version.Builder v = Version.newBuilder();
			v.setVersion(protocolVersion);
			v.setRelease("javalib 0.0.1-dev");
	
			final Authenticate.Builder a = Authenticate.newBuilder();
			a.setUsername(username);
			a.setPassword(password);
			a.addCeltVersions(0x8000000b);
	
			sendMessage(MessageType.Version, v);
			sendMessage(MessageType.Authenticate, a);
			
			connectionHost.setConnectionState(ConnectionState.Connected);
		}

		// Process the stream in separate thread so we can interrupt it if necessary
		// without interrupting the whole connection thread and thus allowing us to
		// disconnect cleanly.
		readingThread = new Thread(new Runnable() {
			public void run() {
				byte[] msg = null;
				try {
					while (socket_.isConnected() && !disconnecting) {

						// Do the socket read outside of any lock as this is blocking operation
						// which might take a while and must be interruptible.
						// Interrupt itself is synchronized through stateLock.
						final short type = in.readShort();
						final int length = in.readInt();
						if (msg == null || msg.length != length) {
							msg = new byte[length];
						}
						in.readFully(msg);

						// Message processing can be done inside stateLock as it shouldn't involve
						// slow network operations.
						synchronized(stateLock) {
							processMsg(MT_CONSTANTS[type], msg);
						}
					}
				} catch (IOException ex) {
					Log.e(Globals.LOG_TAG, ex.toString());
				} finally {
					synchronized(stateLock) {
						connectionHost.setConnectionState(ConnectionState.Disconnecting);
						disconnecting = true;
						
						// The thread is dying so null it. This prevents the waiting loop from
						// spotting that the thread might still be alive briefly after notifyAll.
						readingThread = null;
						stateLock.notifyAll();
					}
				}
			}
		});
		
		readingThread.start();
		
		synchronized (stateLock) {
			while (readingThread != null && readingThread.isAlive()) {
				stateLock.wait();
			}
			readingThread = null;
		}
	}

	private void handleTextMessage(final TextMessage ts) {
		User u = null;
		if (ts.hasActor()) {
			u = findUser(ts.getActor());
		}

		Message msg = new Message();
		msg.timestamp = System.currentTimeMillis();
		msg.message = ts.getMessage();
		msg.actor = u;
		msg.direction = Direction.Received;
		msg.channelIds = ts.getChannelIdCount();
		msg.treeIds = ts.getTreeIdCount();
		connectionHost.messageReceived(msg);
	}

	@SuppressWarnings("unused")
	private void printChanneList() {
		Log.i(Globals.LOG_TAG, "--- begin channel list ---");
		for (final Channel c : channelArray) {
			Log.i(Globals.LOG_TAG, c.toString());
		}
		Log.i(Globals.LOG_TAG, "--- end channel list ---");
	}

	@SuppressWarnings("unused")
	private void printUserList() {
		Log.i(Globals.LOG_TAG, "--- begin user list ---");
		for (final User u : userArray) {
			Log.i(Globals.LOG_TAG, u.toString());
		}
		Log.i(Globals.LOG_TAG, "--- end user list ---");
	}

	private void processMsg(final MessageType t, final byte[] buffer)
			throws IOException {
		switch (t) {
		case UDPTunnel:
			processVoicePacket(buffer);
			break;
		case Ping:
			// ignore
			break;
		case ServerSync:
			final ServerSync ss = ServerSync.parseFrom(buffer);
			session = ss.getSession();

			final User user = findUser(session);
			currentChannel = user.channel;

			pingThread = new Thread(new PingThread(this), "ping");
			pingThread.start();
			Log.i(Globals.LOG_TAG, ">>> " + t);

			ao = new AudioOutput();
			audioOutputThread = new Thread(ao, "audio output");
			audioOutputThread.start();

			final UserState.Builder usb = UserState.newBuilder();
			usb.setSession(session);
//			usb.setPluginContext(ByteString
//					.copyFromUtf8("Manual placement\000test"));
			sendMessage(MessageType.UserState, usb);

			connectionHost.channelsUpdated();
			break;
		case ChannelState:
			final ChannelState cs = ChannelState.parseFrom(buffer);
			Channel c = findChannel(cs.getChannelId());
			if (c != null) {
				if (cs.hasName()) {
					c.name = cs.getName();
				}
				connectionHost.channelsUpdated();
				break;
			}
			
			// New channel
			c = new Channel();
			c.id = cs.getChannelId();
			c.name = cs.getName();
			channelArray.add(c);
			connectionHost.channelsUpdated();
			break;
		case ChannelRemove:
			final ChannelRemove cr = ChannelRemove.parseFrom(buffer);
			channelArray.remove(findChannel(cr.getChannelId()));

			connectionHost.channelsUpdated();
			break;
		case UserState:
			final UserState us = UserState.parseFrom(buffer);
			User u = findUser(us.getSession());
			if (u != null) {
				
				if (us.hasChannelId()) {
					u.channel = us.getChannelId();
					recountChannelUsers();
					if (us.getSession() == session) {
						currentChannel = u.channel;
						connectionHost.currentChannelChanged();
					}
					connectionHost.channelsUpdated();
				}
				
				if (us.getSession() == session) {
					if (us.hasMute() || us.hasSuppress()) {
						if (us.hasMute()) {
							canSpeak = !us.getMute();
						}
						if (us.hasSuppress()) {
							canSpeak = !us.getSuppress();
						}
						connectionHost.userListUpdated();
					}
				}
				break;
			}
			// New user
			u = new User();
			u.session = us.getSession();
			u.name = us.getName();
			u.channel = us.getChannelId();
			recountChannelUsers();
			userArray.add(u);

			connectionHost.userListUpdated();
			break;
		case UserRemove:
			final UserRemove ur = UserRemove.parseFrom(buffer);
			userArray.remove(findUser(ur.getSession()));
			recountChannelUsers();

			connectionHost.userListUpdated();
			break;
		case TextMessage:
			handleTextMessage(TextMessage.parseFrom(buffer));
			break;
		case CryptSetup:
			// TODO: Implementation. See git history for unfinished example.
			break;
		default:
			Log.i(Globals.LOG_TAG, "unhandled message type " + t);
		}
	}

	private void processVoicePacket(final byte[] buffer) {
		final int type = buffer[0] >> 5 & 0x7;
		final int flags = buffer[0] & 0x1f;

		// There is no speex support...
		if (type != UDPMESSAGETYPE_UDPVOICECELTALPHA && type != UDPMESSAGETYPE_UDPVOICECELTBETA) {
			return;
		}

		final PacketDataStream pds = new PacketDataStream(buffer);
		// skip type / flags
		pds.skip(1);
		final long uiSession = pds.readLong();

		final User u = findUser((int) uiSession);
		if (u == null) {
			Log.e(Globals.LOG_TAG, "User session " + uiSession + "not found!");
		}

		ao.addFrameToBuffer(u, pds, flags);
	}

	private void recountChannelUsers() {
		for (final Channel c : channelArray) {
			c.userCount = 0;
		}

		for (final User u : userArray) {
			final Channel c = findChannel(u.channel);
			c.userCount++;
		}
	}
}
