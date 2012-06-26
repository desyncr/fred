package freenet.node.probe;

import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.io.comm.AsyncMessageFilterCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.LongCallback;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Handles starting, routing, and responding to Metropolis-Hastings corrected probes.
 *
 * Possible future additions to these probes' results include:
 * <ul>
 * <li>Checking whether a key is present in the datastore, either only at the endpoint or each node along the way.</li>
 * <li>Success rates for remote requests by HTL; perhaps over some larger amount of time than the past hour.</li>
 * </ul>
 */
public class Probe implements ByteCounter {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	private static volatile boolean logWARNING;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logWARNING = Logger.shouldLog(Logger.LogLevel.WARNING, this);
				logMINOR = Logger.shouldLog(Logger.LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.LogLevel.DEBUG, this);
			}
		});
	}

	private final static String SOURCE_DISCONNECT = "Previous step in probe chain no longer connected.";

	public static final byte MAX_HTL = 50;

	/**
	 * Probability of HTL decrement at HTL = 1.
	 */
	public static final double DECREMENT_PROBABILITY = 0.2;

	/**
	 * In ms, per HTL above HTL = 1.
	 */
	public static final int TIMEOUT_PER_HTL = 3000;

	/**
	 * In ms, to account for probabilistic decrement at HTL = 1.
	 */
	public static final int TIMEOUT_HTL1 = (int)(TIMEOUT_PER_HTL / DECREMENT_PROBABILITY);

	/**
	 * Minute in milliseconds.
	 */
	private static final long MINUTE = 60 * 1000;

	/**
	 * To make the timing less obvious when a node responds with a local result instead of forwarding at
	 * HTL = 1, delay for a number of milliseconds, specifically an exponential distribution with this constant as
	 * its mean.
	 */
	public static final long WAIT_BASE = 1000L;

	/**
	 * Maximum number of milliseconds to wait before sending a response.
	 */
	public static final long WAIT_MAX = 2000L;

	/**
	 * Number of accepted probes in the last minute, keyed by peer.
	 */
	private final Map<PeerNode, Counter> accepted;

	private final Node node;

	private final Timer timer;

	//Whether to respond to different types of probe requests.
	private volatile boolean respondBandwidth;
	private volatile boolean respondBuild;
	private volatile boolean respondIdentifier;
	private volatile boolean respondLinkLengths;
	private volatile boolean respondLocation;
	private volatile boolean respondStoreSize;
	private volatile boolean respondUptime;

	private volatile long probeIdentifier;

	/**
	 * Applies random noise proportional to the input value.
	 * @param input Value to apply noise to.
	 * @return Value +/- Gaussian percentage.
	 */
	private double randomNoise(double input) {
		return input + (node.random.nextGaussian() * 0.01 * input);
	}

	/**
	 * Applies random noise proportional to the input value.
	 * @param input Value to apply noise to.
	 * @return Value +/- Gaussian percentage.
	 */
	private long randomNoise(long input) {
		return input + Math.round(node.random.nextGaussian() * 0.01 * input);
	}

	/**
	 * Counts as probe request transfer.
	 * @param bytes Bytes received.
	 */
	@Override
	public void sentBytes(int bytes) {
		node.nodeStats.probeRequestCtr.sentBytes(bytes);
	}

	/**
	 * Counts as probe request transfer.
	 * @param bytes Bytes received.
	 */
	@Override
	public void receivedBytes(int bytes) {
		node.nodeStats.probeRequestCtr.receivedBytes(bytes);
	}

	/**
	 * No payload in probes.
	 * @param bytes Ignored.
	 */
	@Override
	public void sentPayload(int bytes) {}

	public Probe(final Node node) {
		this.node = node;
		this.accepted = Collections.synchronizedMap(new HashMap<PeerNode, Counter>());
		this.timer = new Timer(true);

		int sortOrder = 0;
		final SubConfig nodeConfig = node.config.get("node");

		nodeConfig.register("probeBandwidth", true, sortOrder++, true, true, "Node.probeBandwidthShort",
			"Node.probeBandwidthLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondBandwidth;
			}

			@Override
			public void set(Boolean val) {
				respondBandwidth = val;
			}
		});
		respondBandwidth = nodeConfig.getBoolean("probeBandwidth");
		nodeConfig.register("probeBuild", true, sortOrder++, true, true, "Node.probeBuildShort",
			"Node.probeBuildLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondBuild;
			}

			@Override
			public void set(Boolean val) {
				respondBuild = val;
			}
		});
		respondBuild = nodeConfig.getBoolean("probeBuild");
		nodeConfig.register("probeIdentifier", true, sortOrder++, true, true,
			"Node.probeRespondIdentifierShort", "Node.probeRespondIdentifierLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondIdentifier;
			}

			@Override
			public void set(Boolean val) {
				respondIdentifier = val;
			}
		});
		respondIdentifier = nodeConfig.getBoolean("probeIdentifier");
		nodeConfig.register("probeLinkLengths", true, sortOrder++, true, true, "Node.probeLinkLengthsShort",
			"Node.probeLinkLengthsLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondLinkLengths;
			}

			@Override
			public void set(Boolean val) {
				respondLinkLengths = val;
			}
		});
		respondLinkLengths = nodeConfig.getBoolean("probeLinkLengths");
		nodeConfig.register("probeLocation", true, sortOrder++, true, true, "Node.probeLocationShort",
			"Node.probeLocationLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondLocation;
			}

			@Override
			public void set(Boolean val) {
				respondLocation = val;
			}
		});
		respondLocation = nodeConfig.getBoolean("probeLocation");
		nodeConfig.register("probeStoreSize", true, sortOrder++, true, true, "Node.probeStoreSizeShort",
			"Node.probeStoreSizeLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondStoreSize;
			}

			@Override
			public void set(Boolean val) {
				respondStoreSize = val;
			}
		});
		respondStoreSize = nodeConfig.getBoolean("probeStoreSize");
		nodeConfig.register("probeUptime", true, sortOrder++, true, true, "Node.probeUptimeShort",
			"Node.probeUptimeLong", new BooleanCallback() {
			@Override
			public Boolean get() {
				return respondUptime;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				respondUptime = val;
			}
		});
		respondUptime = nodeConfig.getBoolean("probeUptime");

		nodeConfig.register("identifier", -1, sortOrder++, true, true, "Node.probeIdentifierShort",
			"Node.probeIdentifierLong", new LongCallback() {
			@Override
			public Long get() {
				return probeIdentifier;
			}

			@Override
			public void set(Long val) {
				probeIdentifier = val;
				//-1 is reserved for picking a random value; don't pick it randomly.
				while(probeIdentifier == -1) probeIdentifier = node.random.nextLong();
			}
		}, false);
		probeIdentifier = nodeConfig.getLong("identifier");

		/*
		 * set() is not used when setting up an option with its default value, so do so manually to avoid using
		 * an identifier of -1.
		 */
		try {
			if(probeIdentifier == -1) {
				nodeConfig.getOption("identifier").setValue("-1");
				//TODO: Store config here as it has changed?
				node.config.store();
			}
		} catch (InvalidConfigValueException e) {
			Logger.error(Probe.class, "node.identifier set() unexpectedly threw.", e);
		} catch (NodeNeedRestartException e) {
			Logger.error(Probe.class, "node.identifier set() unexpectedly threw.", e);
		}
	}

	/**
	 * Sends an outgoing probe request.
	 * @param htl htl for this outgoing probe: should be [1, MAX_HTL]
	 * @param listener Something which implements Probe.Listener and will be called with results.
	 * @see Listener
	 */
	public void start(final byte htl, final long uid, final Type type, final Listener listener) {
		request(DMT.createProbeRequest(htl, uid, type), null, listener);
	}

	/**
	 * Same as its three-argument namesake, but responds to results by passing them on to source.
	 * @param message probe request, (possibly made by DMT.createProbeRequest) containing HTL
	 * @param source node from which the probe request was received. Used to relay back results.
	 */
	public void request(Message message, PeerNode source) {
		request(message, source, new ResultRelay(source, message.getLong(DMT.UID)));
	}

	/**
	 * Processes an incoming probe request.
	 * If the probe has a positive HTL, routes with MH correction and probabilistically decrements HTL.
	 * If the probe comes to have an HTL of zero: (an incoming HTL of zero is taken to be one.)
	 * Returns (as node settings allow) exactly one of:
	 * <ul>
	 *         <li>unique identifier and integer 7-day uptime percentage</li>
	 *         <li>uptime: 48-hour percentage or 7-day percentage</li>
	 *         <li>output bandwidth</li>
	 *         <li>store size</li>
	 *         <li>link lengths</li>
	 *         <li>location</li>
	 *         <li>build number</li>
	 * </ul>
	 *
	 * @param message probe request, containing HTL
	 * @param source node from which the probe request was received. Used to relay back results. If null, it is
	 *               considered to have been sent from the local node.
	 * @param listener listener for probe response.
	 */
	public void request(final Message message, final PeerNode source, final Listener listener) {
		final Long uid = message.getLong(DMT.UID);
		final byte typeCode = message.getByte(DMT.TYPE);
		Type temp;
		if (Type.isValid(typeCode)) {
			temp = Type.valueOf(typeCode);
			if (logDEBUG) Logger.debug(Probe.class, "Probe type is " + temp.name() + ".");
		} else {
			if (logMINOR) Logger.minor(Probe.class, "Invalid probe type " + typeCode + ".");
			listener.onError(Error.UNRECOGNIZED_TYPE, typeCode);
			return;
		}
		final Type type = temp;
		byte htl = message.getByte(DMT.HTL);
		if (htl < 1) {
			if (logWARNING) Logger.warning(Probe.class, "Received out-of-bounds HTL of " + htl + "; interpreting as 1.");
			htl = 1;
		} else if (htl > MAX_HTL) {
			if (logMINOR) Logger.minor(Probe.class, "Received out-of-bounds HTL of " + htl + "; interpreting as " + MAX_HTL + ".");
			htl = MAX_HTL;
		}
		boolean availableSlot = true;
		TimerTask task = null;
		//Allocate one of this peer's probe request slots for 60 seconds; send an overload if none are available.
		synchronized (accepted) {
			//If no counter exists for the current source, add one.
			if (!accepted.containsKey(source)) {
				accepted.put(source, new Counter());
			}
			final Counter counter = accepted.get(source);
			if (counter.value() == Counter.MAX_ACCEPTED) {
				//Set a flag instead of sending inside the lock.
				availableSlot = false;
			} else {
				//There's a free slot; increment the counter.
				counter.increment();
				task = new TimerTask() {
					@Override
					public void run() {
						synchronized (accepted) {
							counter.decrement();
							/* Once the counter hits zero, there's no reason to keep it around as it
							 * can just be recreated when this peer sends another probe request
							 * without changing behavior. To do otherwise would accumulate counters
							 * at zero over time.
							 */
							if (counter.value() == 0) {
								accepted.remove(source);
							}
						}
					}
				};
			}
		}
		if (!availableSlot) {
			//Send an overload error back to the source.
			if (logDEBUG) Logger.debug(Probe.class, "Already accepted maximum number of probes; rejecting incoming.");
			listener.onError(Error.OVERLOAD, null);
			return;
		}
		//One-minute window on acceptance; free up this probe slot in 60 seconds.
		timer.schedule(task, MINUTE);

		/*
		 * Route to a peer, using Metropolis-Hastings correction and ignoring backoff to get a more uniform
		 * endpoint distribution. HTL is decremented before routing so that it's possible to respond locally.
		 */
		htl = probabilisticDecrement(htl);
		if (htl == 0 || !route(type, uid, htl, listener)) {
			long wait = WAIT_MAX;
			while (wait >= WAIT_MAX) wait = (long)(-Math.log(node.random.nextDouble()) * WAIT_BASE / Math.E);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					respond(type, listener);
				}
			}, wait);
		}
	}

	/**
	 * Attempts to route the message to a peer. If HTL is decremented to zero before this is possible, responds.
	 * @return true if the message has been handled; false if the local node should respond to the request.
	 */
	private boolean route(final Type type, final long uid, byte htl, final Listener listener) {
		//Recreate the request so that any sub-messages or unintended fields are not forwarded.
		final Message message = DMT.createProbeRequest(htl, uid, type);
		PeerNode[] peers;
		//Degree of the local node.
		int degree;
		PeerNode candidate;
		//Loop until HTL runs out, in which case return a result, or the probe is relayed on to a peer.
		for (; htl > 0; htl = probabilisticDecrement(htl)) {
			peers = node.getConnectedPeers();
			degree = peers.length;
			//Can't handle a probe request if not connected to any peers.
			if (degree == 0) {
				if (logMINOR) Logger.minor(Probe.class, "Aborting received probe request because there are no connections.");
				/*
				 * If this is a locally-started request, not a relayed one, give an error.
				 * Otherwise, in this case there's nowhere to send the error.
				 */
				listener.onError(Error.DISCONNECTED, null);
				return true;
			}

			candidate = peers[node.random.nextInt(degree)];

			if (candidate.isConnected()) {
				//acceptProbability is the MH correction.
				double acceptProbability;
				int candidateDegree = candidate.getDegree();
				/* Candidate's degree is unknown; fall back to random walk by accepting this candidate
				 * regardless of its degree.
				 */
				if (candidateDegree == 0) acceptProbability = 1.0;
				else acceptProbability = (double)degree / candidateDegree;

				if (logDEBUG) Logger.debug(Probe.class, "acceptProbability is " + acceptProbability);
				if (node.random.nextDouble() < acceptProbability) {
					if (logDEBUG) Logger.debug(Probe.class, "Accepted candidate.");
					//Filter for response to this probe with requested result type.
					final MessageFilter filter = createResponseFilter(type, candidate, uid, htl);
					message.set(DMT.HTL, htl);
					try {
						node.getUSM().addAsyncFilter(filter, new ResultListener(listener), this);
						if (logDEBUG) Logger.debug(Probe.class, "Sending.");
						candidate.sendAsync(message, null, this);
						return true;
					} catch (NotConnectedException e) {
						if (logMINOR) Logger.minor(Probe.class, "Peer became disconnected between check and send attempt.", e);
					} catch (DisconnectedException e) {
						if (logMINOR) Logger.minor(Probe.class, "Peer became disconnected while attempting to add filter.", e);
					}
				}
			}
		}
		return false;
	}

	/**
	 * @param type probe result type requested.
	 * @param candidate node to filter for response from.
	 * @param uid probe request uid, also to be used in any result.
	 * @param htl current probe HTL; used to calculate timeout.
	 * @return filter for the requested result type, probe error, and probe refusal.
	 */
	private MessageFilter createResponseFilter(Type type, PeerNode candidate, long uid, byte htl) {
		final int timeout = (htl - 1) * TIMEOUT_PER_HTL + TIMEOUT_HTL1;
		final MessageFilter filter = MessageFilter.create().setSource(candidate).setField(DMT.UID, uid).setTimeout(timeout);

		switch (type) {
			case BANDWIDTH: filter.setType(DMT.ProbeBandwidth); break;
			case BUILD: filter.setType(DMT.ProbeBuild); break;
			case IDENTIFIER: filter.setType(DMT.ProbeIdentifier); break;
			case LINK_LENGTHS: filter.setType(DMT.ProbeLinkLengths); break;
			case LOCATION: filter.setType(DMT.ProbeLocation); break;
			case STORE_SIZE: filter.setType(DMT.ProbeStoreSize); break;
			case UPTIME_48H:
			case UPTIME_7D: filter.setType(DMT.ProbeUptime); break;
			default: throw new UnsupportedOperationException("Missing filter for " + type.name());
		}

		//Refusal or an error should also be listened for so it can be relayed.
		filter.or(MessageFilter.create().setSource(candidate).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.ProbeRefused)
		      .or(MessageFilter.create().setSource(candidate).setField(DMT.UID, uid).setTimeout(timeout).setType(DMT.ProbeError)));

		return filter;
	}

	/**
	 * Depending on node settings, sends a message to source containing either a refusal or the requested result.
	 */
	private void respond(final Type type, final Listener listener) {

		if (!respondTo(type)) {
			listener.onRefused();
			return;
		}

		switch (type) {
		case BANDWIDTH:
			//1,024 (2^10) bytes per KiB
			listener.onOutputBandwidth(randomNoise(Math.round((double)node.getOutputBandwidthLimit()/(1 << 10))));
			break;
		case BUILD:
			listener.onBuild(node.nodeUpdater.getMainVersion());
			break;
		case IDENTIFIER:
			//7-day uptime with random noise, then quantized.
			long percent = Math.round(randomNoise(100*node.uptime.getUptimeWeek()));
			//Clamp to byte.
			if (percent > Byte.MAX_VALUE) percent = Byte.MAX_VALUE;
			else if (percent < Byte.MIN_VALUE) percent = Byte.MIN_VALUE;
			listener.onIdentifier(probeIdentifier, (byte)percent);
			break;
		case LINK_LENGTHS:
			PeerNode[] peers = node.getConnectedPeers();
			float[] linkLengths = new float[peers.length];
			int i = 0;
			for (PeerNode peer : peers) {
				linkLengths[i++] = (float)randomNoise(Math.min(Math.abs(peer.getLocation() - node.getLocation()),
				                                         1.0 - Math.abs(peer.getLocation() - node.getLocation())));
			}
			listener.onLinkLengths(linkLengths);
			break;
		case LOCATION:
			listener.onLocation((float)node.getLocation());
			break;
		case STORE_SIZE:
			//1,073,741,824 bytes (2^30) per GiB
			listener.onStoreSize(randomNoise(Math.round((double)node.getStoreSize()/(1 << 30))));
			break;
		case UPTIME_48H:
			listener.onUptime((float)randomNoise(100*node.uptime.getUptime()));
			break;
		case UPTIME_7D:
			listener.onUptime((float)randomNoise(100*node.uptime.getUptimeWeek()));
			break;
		default:
			throw new UnsupportedOperationException("Missing response for " + type.name());
		}
	}

	private boolean respondTo(Type type) {
		switch (type){
		case BANDWIDTH: return respondBandwidth;
		case BUILD: return respondBuild;
		case IDENTIFIER: return respondIdentifier;
		case LINK_LENGTHS: return respondLinkLengths;
		case LOCATION: return respondLocation;
		case STORE_SIZE: return respondStoreSize;
		case UPTIME_48H:
		case UPTIME_7D: return respondUptime;
		default: throw new UnsupportedOperationException("Missing permissions check for " + type.name());
		}
	}

	/**
	 * Decrements 20% of the time at HTL 1; otherwise always. This is to protect the responding node, whereas the
	 * anonymity of the node which initiated the request is not a concern.
	 * @param htl current HTL
	 * @return new HTL
	 */
	private byte probabilisticDecrement(byte htl) {
		assert(htl > 0);
		if (htl == 1) {
			if (node.random.nextDouble() < DECREMENT_PROBABILITY) return 0;
			return 1;
		}
		return (byte)(htl - 1);
	}

	/**
	 * Filter listener which determines the type of result and calls the appropriate probe listener method.
	 */
	private class ResultListener implements AsyncMessageFilterCallback {

		private final Listener listener;

		/**
		 * @param listener to call appropriate methods for events such as matched messages or timeout.
		 */
		public ResultListener(Listener listener) {
			this.listener = listener;
		}

		@Override
		public void onDisconnect(PeerContext context) {
			if (logDEBUG) Logger.debug(Probe.class, "Next node in chain disconnected.");
			listener.onError(Error.DISCONNECTED, null);
		}

		/**
		 * Parses provided message and calls appropriate Probe.Listener method for the type of result.
		 * @param message Probe result.
		 */
		@Override
		public void onMatched(Message message) {
			if(logDEBUG) Logger.debug(Probe.class, "Matched " + message.getSpec().getName());
			if (message.getSpec().equals(DMT.ProbeBandwidth)) {
				listener.onOutputBandwidth(message.getLong(DMT.OUTPUT_BANDWIDTH_UPPER_LIMIT));
			} else if (message.getSpec().equals(DMT.ProbeBuild)) {
				listener.onBuild(message.getInt(DMT.BUILD));
			} else if (message.getSpec().equals(DMT.ProbeIdentifier)) {
				listener.onIdentifier(message.getLong(DMT.IDENTIFIER), message.getByte(DMT.UPTIME_PERCENT));
			} else if (message.getSpec().equals(DMT.ProbeLinkLengths)) {
				listener.onLinkLengths(message.getFloatArray(DMT.LINK_LENGTHS));
			} else if (message.getSpec().equals(DMT.ProbeLocation)) {
				listener.onLocation(message.getFloat(DMT.LOCATION));
			} else if (message.getSpec().equals(DMT.ProbeStoreSize)) {
				listener.onStoreSize(message.getLong(DMT.STORE_SIZE));
			} else if (message.getSpec().equals(DMT.ProbeUptime)) {
				listener.onUptime(message.getFloat(DMT.UPTIME_PERCENT));
			} else if (message.getSpec().equals(DMT.ProbeError)) {
				final byte rawError = message.getByte(DMT.TYPE);
				if (Error.isValid(rawError)) {
					listener.onError(Error.valueOf(rawError), null);
				} else {
					//Not recognized locally.
					listener.onError(Error.UNKNOWN, rawError);
				}
			} else if (message.getSpec().equals(DMT.ProbeRefused)) {
				listener.onRefused();
			}  else {
				throw new UnsupportedOperationException("Missing handling for " + message.getSpec().getName());
			}
		}

		@Override
		public void onRestarted(PeerContext context) {}

		@Override
		public void onTimeout() {
			if (logDEBUG) Logger.debug(Probe.class, "Timed out.");
			listener.onError(Error.TIMEOUT, null);
		}

		@Override
		public boolean shouldTimeout() {
			return false;
		}
	}

	/**
	 * Listener which relays responses to the node specified during construction. Used for received probe requests.
	 * This leads to reconstructing the messages, but removes potentially harmful sub-messages and also removes the
	 * need for duplicate message sending code elsewhere, If the result includes a trace,,this would be the place
	 * to add local results to it.
	 */
	private class ResultRelay implements Listener {

		private final PeerNode source;
		private final Long uid;

		/**
		 * @param source peer from which the request was received and to which send the response.
		 * @throws IllegalArgumentException if source is null.
		 */
		public ResultRelay(PeerNode source, Long uid) {
			this.source = source;
			this.uid = uid;
		}

		private void send(Message message) {
			if (!source.isConnected()) {
				if (logDEBUG) Logger.debug(Probe.class, SOURCE_DISCONNECT);
				return;
			}
			if (logDEBUG) Logger.debug(Probe.class, "Relaying " + message.getSpec().getName() + " back" +
			                                          " to " + source.userToString());
			try {
				source.sendAsync(message, null, Probe.this);
			} catch (NotConnectedException e) {
				if (logDEBUG) Logger.debug(Probe.class, SOURCE_DISCONNECT, e);
			}
		}

		@Override
		public void onError(Error error, Byte code) {
			send(DMT.createProbeError(uid, error));
		}

		@Override
		public void onRefused() {
			send(DMT.createProbeRefused(uid));
		}

		@Override
		public void onOutputBandwidth(long outputBandwidth) {
			send(DMT.createProbeBandwidth(uid, outputBandwidth));
		}

		@Override
		public void onBuild(int build) {
			send(DMT.createProbeBuild(uid, build));
		}

		@Override
		public void onIdentifier(long identifier, byte uptimePercentage) {
			send(DMT.createProbeIdentifier(uid, identifier, uptimePercentage));
		}

		@Override
		public void onLinkLengths(float[] linkLengths) {
			send(DMT.createProbeLinkLengths(uid, linkLengths));
		}

		@Override
		public void onLocation(float location) {
			send(DMT.createProbeLocation(uid, location));
		}

		@Override
		public void onStoreSize(long storeSize) {
			send(DMT.createProbeStoreSize(uid, storeSize));
		}

		@Override
		public void onUptime(float uptimePercentage) {
			send(DMT.createProbeUptime(uid, uptimePercentage));
		}
	}
}
