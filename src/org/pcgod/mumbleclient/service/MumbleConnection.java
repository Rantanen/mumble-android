package org.pcgod.mumbleclient.service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import junit.framework.Assert;
import net.sf.mumble.MumbleProto.Authenticate;
import net.sf.mumble.MumbleProto.Version;

import org.pcgod.mumbleclient.Globals;
import org.pcgod.mumbleclient.service.MumbleProtocol.MessageType;

import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

/**
 * Maintains connection to the server and implements the low level communication
 * protocol.
 *
 * This class should support calls from both the main application thread and its
 * own connection thread. As a result the disconnecting state is quite
 * complicated. Disconnect can basically happen at any point as the server might
 * cut the connection due to restart or kick.
 *
 * When disconnection happens, the connection reports "Disconnected" state and
 * stops handling incoming or outgoing messages. Since at this point some of
 * the sockets might already be closed there is no use waiting on Disconnected
 * reporting until all the other threads, such as PingThread or RecordThread
 * have been stopped.
 *
 * @author pcgod
 */
public class MumbleConnection implements Runnable {

	public static final int UDP_BUFFER_SIZE = 2048;

	private final MumbleConnectionHost connectionHost;
	private Protocol protocol;

	private SSLSocket tcpSocket;
	private DataInputStream in;
	private DataOutputStream out;
	private DatagramSocket udpSocket;
	private long useUdpUntil;
	boolean usingUdp = false;

	private boolean disconnecting = false;

	private final String host;
	private final int port;
	private final String username;
	private final String password;

	private Thread udpReaderThread;
	private Thread tcpReaderThread;
	private final Object stateLock = new Object();
	private final CryptState cryptState = new CryptState();

	/**
	 * Constructor for new connection thread.
	 *
	 * This thread should be started shortly after construction. Construction
	 * sets the connection state for the host to "Connecting" even if the actual
	 * connection won't be attempted until the thread has been started.
	 *
	 * This is to combat an issue where the Service is asked to connect and the
	 * thread is started but the thread isn't given execution time before
	 * another activity checks for connection state and finds out the service is
	 * in Disconnected state.
	 *
	 * @param connectionHost_
	 *            Host interface for this Connection
	 * @param audioHost_
	 *            Host interface for underlying AudioOutputs.
	 * @param host_
	 *            Mumble server host address
	 * @param port_
	 *            Mumble server port
	 * @param username_
	 *            Username
	 * @param password_
	 *            Server password
	 * @param audioSettings_
	 *            Settings for the AudioOutput wrapped in a class so
	 *            MumbleConnection doesn't need to know what it has to pass on.
	 */
	public MumbleConnection(
		final MumbleConnectionHost connectionHost,
		final String host,
		final int port,
		final String username,
		final String password) {

		this.connectionHost = connectionHost;
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;

		connectionHost.setConnectionState(MumbleConnectionHost.STATE_CONNECTING);
	}

	public final void disconnect() {
		synchronized (stateLock) {
			if (disconnecting == true) {
				return;
			}

			disconnecting = true;

			if (tcpReaderThread != null) {
				tcpReaderThread.interrupt();
			}
			if (udpReaderThread != null) {
				udpReaderThread.interrupt();
			}

			connectionHost.setConnectionState(MumbleConnectionHost.STATE_DISCONNECTING);
			stateLock.notifyAll();
		}
	}

	public ByteString getNonce() {
		return cryptState.getClientNonce();
	}

	public final boolean isConnectionAlive() {
		return !disconnecting && !connectionDead();
	}

	public final boolean isSameServer(
		final String host_,
		final int port_,
		final String username_,
		final String password_) {
		return host.equals(host_) && port == port_ &&
			   username.equals(username_) && password.equals(password_);
	}

	public void refreshUdpLimit(long limit) {
		useUdpUntil = limit;
	}

	@Override
	public final void run() {
		Assert.assertNotNull(protocol);

		boolean connected = false;
		try {
			try {
				Log.i(Globals.LOG_TAG, String.format(
					"Connecting to host \"%s\", port %s",
					host,
					port));

				connectTcp();
				connectUdp();
				connected = true;
			} catch (final UnknownHostException e) {
				final String errorString = String.format(
					"Host \"%s\" unknown",
					host);
				connectionHost.setError(errorString);
				Log.e(Globals.LOG_TAG, errorString, e);
			} catch (final ConnectException e) {
				final String errorString = "The host refused connection";
				connectionHost.setError(errorString);
				Log.e(Globals.LOG_TAG, errorString, e);
			} catch (final KeyManagementException e) {
				Log.e(Globals.LOG_TAG, String.format(
					"Could not connect to Mumble server \"%s:%s\"",
					host,
					port), e);
			} catch (final NoSuchAlgorithmException e) {
				Log.e(Globals.LOG_TAG, String.format(
					"Could not connect to Mumble server \"%s:%s\"",
					host,
					port), e);
			} catch (final IOException e) {
				Log.e(Globals.LOG_TAG, String.format(
					"Could not connect to Mumble server \"%s:%s\"",
					host,
					port), e);
			}

			// If we couldn't finish connecting, return.
			if (!connected) {
				return;
			}

			synchronized (stateLock) {
				connectionHost.setConnectionState(MumbleConnectionHost.STATE_CONNECTED);
			}

			try {
				handleProtocol();
			} catch (final IOException e) {
				final String errorString = String.format(
					"Connection lost",
					host);
				connectionHost.setError(errorString);
				Log.e(Globals.LOG_TAG, errorString, e);
			} catch (final InterruptedException e) {
				final String errorString = String.format(
					"Connection lost",
					host);
				connectionHost.setError(errorString);
				Log.e(Globals.LOG_TAG, errorString, e);
			}

		} finally {
			synchronized (stateLock) {
				disconnecting = true;
				connectionHost.setConnectionState(MumbleConnectionHost.STATE_DISCONNECTED);
			}

			cleanConnection();
		}
	}

	public final void sendMessage(
		final MessageType t,
		final MessageLite.Builder b) {
		final MessageLite m = b.build();
		final short type = (short) t.ordinal();
		final int length = m.getSerializedSize();

		synchronized (stateLock) {
			if (disconnecting)
				return;

			try {
				synchronized (out) {
					out.writeShort(type);
					out.writeInt(length);
					m.writeTo(out);
				}
			} catch (final IOException e) {
				handleSendingException(e);
			}
		}

		if (t != MessageType.Ping) {
			Log.d(Globals.LOG_TAG, "<<< " + t);
		}
	}

	public final void sendUdpMessage(
		final byte[] buffer,
		final int length,
		final boolean forceUdp) {
		// FIXME: This would break things because we don't handle nonce resync messages
//		if (!cryptState.isInitialized()) {
//			return;
//		}

		if (forceUdp || useUdpUntil > System.currentTimeMillis()) {
			if (!usingUdp && !forceUdp) {
				Log.i(Globals.LOG_TAG, "MumbleConnection: UDP enabled");
				usingUdp = true;
			}

			final byte[] encryptedBuffer = cryptState.encrypt(buffer, length);
			final DatagramPacket outPacket = new DatagramPacket(
				encryptedBuffer,
				encryptedBuffer.length);

			try {
				outPacket.setAddress(Inet4Address.getByName(host));
			} catch (final UnknownHostException e) {
				reportError(String.format("Cannot resolve host %s", host), e);
				disconnect();
				return;
			}
			outPacket.setPort(port);

			synchronized (stateLock) {
				if (disconnecting)
					return;

				try {
					udpSocket.send(outPacket);
				} catch (final IOException e) {
					handleSendingException(e);
				}
			}
		} else {
			if (usingUdp) {
				Log.i(Globals.LOG_TAG, "MumbleConnection: UDP disabled");
				usingUdp = false;
			}

			final short type = (short) MessageType.UDPTunnel.ordinal();

			synchronized (stateLock) {
				if (disconnecting)
					return;

				synchronized (out) {

					try {
						out.writeShort(type);
						out.writeInt(length);
						out.write(buffer, 0, length);
					} catch (final IOException e) {
						handleSendingException(e);
					}
				}
			}
		}
	}

	private void reportError(String error) {
		connectionHost.setError(error);
		Log.e(Globals.LOG_TAG, error);
	}

	private void reportError(String error, Exception e) {
		connectionHost.setError(String.format(error));
		Log.e(Globals.LOG_TAG, error, e);
	}

	private boolean handleSendingException(IOException e) {
		// If we are already disconnecting, just ignore this.
		if (disconnecting)
			return true;

		// Otherwise see if we should be disconnecting really.
		if (connectionDead()) {
			disconnect();
			reportError(
				String.format("Connection lost: %s", e.getMessage()),
				e);
		} else {
			// Connection is alive but we still couldn't send message?
			reportError(
				String.format("Error while sending message: %s", e.getMessage()),
				e);
		}

		return false;
	}

	public void syncCryptKeys(byte[] key, byte[] clientNonce, byte[] serverNonce) {
		Assert.assertNotNull(serverNonce);

		if (key != null && clientNonce != null) {
			cryptState.setKeys(key, clientNonce, serverNonce);
		} else {
			cryptState.setServerNonce(serverNonce);
		}
	}

	public Thread start(Protocol protocol) {
		this.protocol = protocol;

		final Thread t = new Thread(this, "MumbleConnection");
		t.start();
		return t;
	}

	private void cleanConnection() {
		// FIXME: These throw exceptions for some reason.
		// Even with the checks in place
		if (tcpSocket.isConnected()) {
			try {
				tcpSocket.close();
			} catch (final IOException e) {
				Log.e(
					Globals.LOG_TAG,
					"IO error while closing the tcp socket",
					e);
			}
		}
		if (udpSocket.isConnected()) {
			udpSocket.close();
		}
	}

	private boolean connectionDead() {
		// If either of the sockets is closed, play dead.
		if (tcpSocket.isClosed() || udpSocket.isClosed())
			return true;

		// If the TCP connection has been lost, play dead.
		if (!tcpSocket.isConnected())
			return true;

		return false;
	}

	private void connectTcp() throws NoSuchAlgorithmException,
		KeyManagementException, IOException, UnknownHostException {
		final SSLContext ctx_ = SSLContext.getInstance("TLS");
		ctx_.init(
			null,
			new TrustManager[] { new LocalSSLTrustManager() },
			null);
		final SSLSocketFactory factory = ctx_.getSocketFactory();
		tcpSocket = (SSLSocket) factory.createSocket(host, port);
		tcpSocket.setUseClientMode(true);
		tcpSocket.setEnabledProtocols(new String[] { "TLSv1" });
		tcpSocket.startHandshake();

		Log.i(Globals.LOG_TAG, "TCP/SSL socket opened");
	}

	private void connectUdp() throws SocketException, UnknownHostException {
		udpSocket = new DatagramSocket();
		udpSocket.connect(Inet4Address.getByName(host), port);

		Log.i(Globals.LOG_TAG, "UDP Socket opened");
	}

	private void handleProtocol() throws IOException,
		InterruptedException {
		synchronized (stateLock) {
			if (disconnecting) {
				return;
			}
		}

		out = new DataOutputStream(tcpSocket.getOutputStream());
		in = new DataInputStream(tcpSocket.getInputStream());

		final Version.Builder v = Version.newBuilder();
		v.setVersion(Globals.PROTOCOL_VERSION);
		v.setRelease("MumbleAndroid 0.0.1-dev");

		final Authenticate.Builder a = Authenticate.newBuilder();
		a.setUsername(username);
		a.setPassword(password);
		a.addCeltVersions(Globals.CELT_VERSION);

		sendMessage(MessageType.Version, v);
		sendMessage(MessageType.Authenticate, a);

		synchronized (stateLock) {
			if (disconnecting) {
				return;
			}
		}

		// Process the stream in separate thread so we can interrupt it if necessary
		// without interrupting the whole connection thread and thus allowing us to
		// disconnect cleanly.
		final MumbleSocketReader tcpReader = new MumbleSocketReader(stateLock) {
			private byte[] msg = null;

			@Override
			public boolean isRunning() {
				return tcpSocket.isConnected() && !disconnecting &&
					   super.isRunning();
			}

			@Override
			protected void process() throws IOException {
				final short type = in.readShort();
				final int length = in.readInt();
				if (msg == null || msg.length != length) {
					msg = new byte[length];
				}
				in.readFully(msg);

				protocol.processTcp(type, msg);
			}
		};

		final MumbleSocketReader udpReader = new MumbleSocketReader(stateLock) {
			private final DatagramPacket packet = new DatagramPacket(
				new byte[UDP_BUFFER_SIZE],
				UDP_BUFFER_SIZE);

			@Override
			public boolean isRunning() {
				return udpSocket.isConnected() && !disconnecting &&
					   super.isRunning();
			}

			@Override
			protected void process() throws IOException {
				udpSocket.receive(packet);

				final byte[] buffer = cryptState.decrypt(
					packet.getData(),
					packet.getLength());

				// Decrypt might return null if the buffer was total garbage.
				if (buffer == null) {
					return;
				}

				protocol.processUdp(buffer, buffer.length);
			}
		};

		tcpReaderThread = new Thread(tcpReader, "TCP Reader");
		udpReaderThread = new Thread(udpReader, "UDP Reader");

		tcpReaderThread.start();
		udpReaderThread.start();

		synchronized (stateLock) {
			while (tcpReaderThread.isAlive() && tcpReader.isRunning() &&
				   udpReaderThread.isAlive() && udpReader.isRunning()) {
				stateLock.wait();
			}

			disconnecting = true;
			connectionHost.setConnectionState(MumbleConnectionHost.STATE_DISCONNECTING);

			// Interrupt both threads in case only one of them was closed.
			tcpReaderThread.interrupt();
			udpReaderThread.interrupt();

			tcpReaderThread = null;
			udpReaderThread = null;
		}
	}
}
