/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import org.atalk.util.MediaType;
import org.jivesoftware.smackx.colibri.ColibriConferenceIQ;

import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.JingleUtils;
import net.java.sip.communicator.service.protocol.CallPeer;
import net.java.sip.communicator.service.protocol.OperationFailedException;

import org.atalk.service.neomedia.*;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smackx.jingle.*;
import org.jivesoftware.smackx.jingle.element.JingleContent;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * A {@link TransportManagerJabberImpl} implementation that would only gather a single candidate
 * pair (i.e. RTP and RTCP).
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 * @author Hristo Terezov
 */
public class RawUdpTransportManager extends TransportManagerJabberImpl
{
    /**
     * The list of <tt>JingleContent</tt>s which represents the local counterpart of the
     * negotiation between the local and the remote peers.
     */
    private List<JingleContent> local;

    /**
     * The collection of <tt>JingleContent</tt>s which represents the remote counterpart of
     * the negotiation between the local and the remote peers.
     */
    private final List<Iterable<JingleContent>> remotes = new LinkedList<>();

    /**
     * Creates a new instance of this transport manager, binding it to the specified peer.
     *
     * @param callPeer the {@link CallPeer} whose traffic we will be taking care of.
     */
    public RawUdpTransportManager(CallPeerJabberImpl callPeer)
    {
        super(callPeer);
    }

    /**
     * {@inheritDoc}
     */
    protected ExtensionElement createTransport(String media)
            throws OperationFailedException
    {
        MediaType mediaType = MediaType.parseString(media);
        return createTransport(mediaType, getStreamConnector(mediaType));
    }

    /**
     * Creates a raw UDP transport element according to a specific <tt>StreamConnector</tt>.
     *
     * @param mediaType the <tt>MediaType</tt> of the <tt>MediaStream</tt> which uses the specified
     * <tt>connector</tt> or <tt>channel</tt>
     * @param connector the <tt>StreamConnector</tt> to be described within the transport element
     * @return a {@link RawUdpTransportExtension} containing the RTP and RTCP candidates of
     * the specified <tt>connector</tt>
     */
    private RawUdpTransportExtension createTransport(MediaType mediaType, StreamConnector connector)
    {
        RawUdpTransportExtension ourTransport = new RawUdpTransportExtension();
        int generation = getCurrentGeneration();

        // create and add candidates that correspond to the stream connector
        // RTP
        CandidateExtension rtpCand = new CandidateExtension();

        rtpCand.setComponent(CandidateExtension.RTP_COMPONENT_ID);
        rtpCand.setGeneration(generation);
        rtpCand.setID(getNextID());
        rtpCand.setType(CandidateType.host);

        DatagramSocket dataSocket = connector.getDataSocket();

        rtpCand.setIP(dataSocket.getLocalAddress().getHostAddress());
        rtpCand.setPort(dataSocket.getLocalPort());

        ourTransport.addCandidate(rtpCand);

        // RTCP
        CandidateExtension rtcpCand = new CandidateExtension();

        rtcpCand.setComponent(CandidateExtension.RTCP_COMPONENT_ID);
        rtcpCand.setGeneration(generation);
        rtcpCand.setID(getNextID());
        rtcpCand.setType(CandidateType.host);

        DatagramSocket controlSocket = connector.getControlSocket();

        rtcpCand.setIP(controlSocket.getLocalAddress().getHostAddress());
        rtcpCand.setPort(controlSocket.getLocalPort());

        ourTransport.addCandidate(rtcpCand);

        return ourTransport;
    }

    /**
     * {@inheritDoc}
     */
    protected ExtensionElement createTransportPacketExtension()
    {
        return new RawUdpTransportExtension();
    }

    /**
     * Implements {@link TransportManagerJabberImpl#getStreamTarget(MediaType)}. Gets the
     * <tt>MediaStreamTarget</tt> to be used as the <tt>target</tt> of the <tt>MediaStream</tt> with
     * a specific <tt>MediaType</tt>.
     *
     * @param mediaType the <tt>MediaType</tt> of the <tt>MediaStream</tt> which is to have its
     * <tt>target</tt> set to the returned <tt>MediaStreamTarget</tt>
     * @return the <tt>MediaStreamTarget</tt> to be used as the <tt>target</tt> of the
     * <tt>MediaStream</tt> with the specified <tt>MediaType</tt>
     * @see TransportManagerJabberImpl#getStreamTarget(MediaType)
     */
    @Override
    public MediaStreamTarget getStreamTarget(MediaType mediaType)
    {
        ColibriConferenceIQ.Channel channel = getColibriChannel(mediaType, true /* local */);
        MediaStreamTarget streamTarget = null;

        if (channel == null) {
            String media = mediaType.toString();

            for (Iterable<JingleContent> remote : remotes) {
                for (JingleContent content : remote) {
                    RtpDescriptionExtension rtpDescription
                        = content.getFirstChildOfType(RtpDescriptionExtension.class);

                    if (media.equals(rtpDescription.getMedia())) {
                        streamTarget = JingleUtils.extractDefaultTarget(content);
                        break;
                    }
                }
            }
        }
        else {
            IceUdpTransportExtension transport = channel.getTransport();

            if (transport != null)
                streamTarget = JingleUtils.extractDefaultTarget(transport);
            if (streamTarget == null) {
                /*
                 * For the purposes of compatibility with legacy Jitsi Videobridge, support the
                 * channel attributes host, rtpPort and rtcpPort.
                 */
                @SuppressWarnings("deprecation")
                String host = channel.getHost();

                if (host != null) {
                    @SuppressWarnings("deprecation")
                    int rtpPort = channel.getRTPPort();
                    @SuppressWarnings("deprecation")
                    int rtcpPort = channel.getRTCPPort();

                    streamTarget = new MediaStreamTarget(new InetSocketAddress(host, rtpPort),
                            new InetSocketAddress(host, rtcpPort));
                }
            }
        }
        return streamTarget;
    }

    /**
     * Implements {@link TransportManagerJabberImpl#getXmlNamespace()}. Gets the XML namespace of
     * the Jingle transport implemented by this <tt>TransportManagerJabberImpl</tt>.
     *
     * @return the XML namespace of the Jingle transport implemented by this <tt>TransportManagerJabberImpl</tt>
     * @see TransportManagerJabberImpl#getXmlNamespace()
     */
    @Override
    public String getXmlNamespace()
    {
        return ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_RAW_UDP_0;
    }

    /**
     * Removes a content with a specific name from the transport-related part of the session
     * represented by this <tt>TransportManagerJabberImpl</tt> which may have been reported through
     * previous calls to the <tt>startCandidateHarvest</tt> and <tt>startConnectivityEstablishment</tt> methods.
     *
     * @param name the name of the content to be removed from the transport-related part of the session
     * represented by this <tt>TransportManagerJabberImpl</tt>
     * @see TransportManagerJabberImpl#removeContent(String)
     */
    @Override
    public void removeContent(String name)
    {
        if (local != null)
            removeContent(local, name);
        removeRemoteContent(name);
    }

    /**
     * Removes a content with a specific name from the remote counterpart of the negotiation between
     * the local and the remote peers.
     *
     * @param name the name of the content to be removed from the remote counterpart of the negotiation
     * between the local and the remote peers
     */
    private void removeRemoteContent(String name)
    {
        for (Iterator<Iterable<JingleContent>> remoteIter = remotes.iterator(); remoteIter.hasNext(); ) {
            Iterable<JingleContent> remote = remoteIter.next();

            /*
             * Once the remote content is removed, make sure that we are not retaining sets which do
             * not have any contents.
             */
            if ((removeContent(remote, name) != null) && !remote.iterator().hasNext()) {
                remoteIter.remove();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    protected ExtensionElement startCandidateHarvest(JingleContent theirContent,
            JingleContent ourContent, TransportInfoSender transportInfoSender, String media)
            throws OperationFailedException
    {
        return createTransportForStartCandidateHarvest(media);
    }

    /**
     * Starts transport candidate harvest. This method should complete rapidly and, in case of
     * lengthy procedures like STUN/TURN/UPnP candidate harvests are necessary, they should be
     * executed in a separate thread. Candidate harvest would then need to be concluded in the
     * {@link #wrapupCandidateHarvest()} method which would be called once we absolutely need the candidates.
     *
     * @param theirOffer a media description offer that we've received from the remote party and that we should
     * use in case we need to know what transports our peer is using.
     * @param ourAnswer the content descriptions that we should be adding our transport lists to (although not
     * necessarily in this very instance).
     * @param transportInfoSender the <tt>TransportInfoSender</tt> to be used by this
     * <tt>TransportManagerJabberImpl</tt> to send <tt>transport-info</tt> <tt>Jingle</tt>s
     * from the local peer to the remote peer if this <tt>TransportManagerJabberImpl</tt>
     * wishes to utilize <tt>transport-info</tt>. Local candidate addresses sent by this
     * <tt>TransportManagerJabberImpl</tt> in <tt>transport-info</tt> are expected to not be
     * included in the result of {@link #wrapupCandidateHarvest()}.
     * @throws OperationFailedException if we fail to allocate a port number.
     * @see TransportManagerJabberImpl#startCandidateHarvest(List, List, TransportInfoSender)
     */
    @Override
    public void startCandidateHarvest(List<JingleContent> theirOffer,
            List<JingleContent> ourAnswer, TransportInfoSender transportInfoSender)
            throws OperationFailedException
    {
        this.local = ourAnswer;
        super.startCandidateHarvest(theirOffer, ourAnswer, transportInfoSender);
    }

    /**
     * Overrides the super implementation in order to remember the remote counterpart of the
     * negotiation between the local and the remote peer for subsequent calls to
     * {@link #getStreamTarget(MediaType)}.
     *
     * @param remote the collection of <tt>JingleContent</tt>s which represents the remote
     * counterpart of the negotiation between the local and the remote peer
     * @return <tt>true</tt> because <tt>RawUdpTransportManager</tt> does not perform connectivity checks
     * @see TransportManagerJabberImpl#startConnectivityEstablishment(Iterable)
     */
    @Override
    public boolean startConnectivityEstablishment(Iterable<JingleContent> remote)
        throws OperationFailedException
    {
        if ((remote != null) && !remotes.contains(remote)) {
            /*
             * The state of the session in Jingle is maintained by each peer and is modified by
             * content-add and content-remove. The remotes field of this RawUdpTransportManager
             * represents the state of the session with respect to the remote peer. When the remote
             * peer tells us about a specific set of contents, make sure that it is the only record
             * we will have with respect to the specified set of contents.
             */
            for (JingleContent content : remote)
                removeRemoteContent(content.getName());

            remotes.add(remote);
        }
        return super.startConnectivityEstablishment(remote);
    }

    /**
     * Simply returns the list of local candidates that we gathered during the harvest. This is a
     * raw UDP transport manager so there's no real wrapping up to do.
     *
     * @return the list of local candidates that we gathered during the harvest
     * @see TransportManagerJabberImpl#wrapupCandidateHarvest()
     */
    @Override
    public List<JingleContent> wrapupCandidateHarvest()
    {
        return local;
    }

    /**
     * Returns the extended type of the candidate selected if this transport manager is using ICE.
     *
     * @param streamName The stream name (AUDIO, VIDEO);
     * @return The extended type of the candidate selected if this transport manager is using ICE. Otherwise, returns null.
     */
    @Override
    public String getICECandidateExtendedType(String streamName)
    {
        return null;
    }

    /**
     * Returns the current state of ICE processing.
     *
     * @return the current state of ICE processing.
     */
    @Override
    public String getICEState()
    {
        return null;
    }

    /**
     * Returns the ICE local host address.
     *
     * @param streamName The stream name (AUDIO, VIDEO);
     * @return the ICE local host address if this transport manager is using ICE. Otherwise, returns null.
     */
    @Override
    public InetSocketAddress getICELocalHostAddress(String streamName)
    {
        return null;
    }

    /**
     * Returns the ICE remote host address.
     *
     * @param streamName The stream name (AUDIO, VIDEO);
     * @return the ICE remote host address if this transport manager is using ICE. Otherwise, returns null.
     */
    @Override
    public InetSocketAddress getICERemoteHostAddress(String streamName)
    {
        return null;
    }

    /**
     * Returns the ICE local reflexive address (server or peer reflexive).
     *
     * @param streamName The stream name (AUDIO, VIDEO);
     * @return the ICE local reflexive address. May be null if this transport manager is not using
     * ICE or if there is no reflexive address for the local candidate used.
     */
    @Override
    public InetSocketAddress getICELocalReflexiveAddress(String streamName)
    {
        return null;
    }

    /**
     * Returns the ICE remote reflexive address (server or peer reflexive).
     *
     * @param streamName The stream name (AUDIO, VIDEO);
     * @return the ICE remote reflexive address. May be null if this transport manager is not using
     * ICE or if there is no reflexive address for the remote candidate used.
     */
    @Override
    public InetSocketAddress getICERemoteReflexiveAddress(String streamName)
    {
        return null;
    }

    /**
     * Returns the ICE local relayed address (server or peer relayed).
     *
     * @param streamName The stream name (AUDIO, VIDEO);
     * @return the ICE local relayed address. May be null if this transport manager is not using ICE
     * or if there is no relayed address for the local candidate used.
     */
    @Override
    public InetSocketAddress getICELocalRelayedAddress(String streamName)
    {
        return null;
    }

    /**
     * Returns the ICE remote relayed address (server or peer relayed).
     *
     * @param streamName The stream name (AUDIO, VIDEO);
     * @return the ICE remote relayed address. May be null if this transport manager is not using
     * ICE or if there is no relayed address for the remote candidate used.
     */
    @Override
    public InetSocketAddress getICERemoteRelayedAddress(String streamName)
    {
        return null;
    }

    /**
     * Returns the total harvesting time (in ms) for all harvesters.
     *
     * @return The total harvesting time (in ms) for all the harvesters. 0 if the ICE agent is null,
     * or if the agent has nevers harvested.
     */
    @Override
    public long getTotalHarvestingTime()
    {
        return 0;
    }

    /**
     * Returns the harvesting time (in ms) for the harvester given in parameter.
     *
     * @param harvesterName The class name if the harvester.
     * @return The harvesting time (in ms) for the harvester given in parameter. 0 if this harvester
     * does not exists, if the ICE agent is null, or if the agent has never harvested with this harvester.
     */
    @Override
    public long getHarvestingTime(String harvesterName)
    {
        return 0;
    }

    /**
     * Returns the number of harvesting for this agent.
     *
     * @return The number of harvesting for this agent.
     */
    @Override
    public int getNbHarvesting()
    {
        return 0;
    }

    /**
     * Returns the number of harvesting time for the harvester given in parameter.
     *
     * @param harvesterName The class name if the harvester.
     * @return The number of harvesting time for the harvester given in parameter.
     */
    @Override
    public int getNbHarvesting(String harvesterName)
    {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRtcpmux(boolean rtcpmux)
    {
        if (rtcpmux) {
            throw new IllegalArgumentException("rtcp mux not supported by " + getClass().getSimpleName());
        }
    }
}
