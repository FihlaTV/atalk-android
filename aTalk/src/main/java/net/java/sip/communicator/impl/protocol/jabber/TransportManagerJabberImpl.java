/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.JingleUtils;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.media.TransportManager;

import org.atalk.service.neomedia.MediaStreamTarget;
import org.atalk.service.neomedia.StreamConnector;
import org.atalk.util.MediaType;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.IQ;
import org.jxmpp.jid.Jid;
import org.jivesoftware.smackx.colibri.ColibriConferenceIQ;
import org.jivesoftware.smackx.jingle.IceUdpTransportExtension;
import org.jivesoftware.smackx.jingle.RtpDescriptionExtension;
import org.jivesoftware.smackx.jingle.element.JingleContent;

import java.net.InetAddress;
import java.util.*;

/**
 * <tt>TransportManager</tt>s gather local candidates for incoming and outgoing calls. Their work
 * starts by calling a start method which, using the remote peer's session description, would start
 * the harvest. Calling a second wrap up method would deliver the candidate harvest, possibly after
 * blocking if it has not yet completed.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 * @author Eng Chong Meng
 */
public abstract class TransportManagerJabberImpl extends TransportManager<CallPeerJabberImpl>
{
    /**
     * The ID that we will be assigning to our next candidate. We use <tt>int</tt>s for
     * inter-operability reasons (Emil: I believe that GTalk uses <tt>int</tt>s. If that turns out
     * not to be the case we can stop using <tt>int</tt>s here if that's an issue).
     */
    private static int nextID = 1;

    /**
     * The information pertaining to the Jisti Videobridge conference which the local peer
     * represented by this instance is a focus of. It gives a view of the whole Jitsi Videobridge
     * conference managed by the associated <tt>CallJabberImpl</tt> which provides information
     * specific to this <tt>TransportManager</tt> only.
     */
    private ColibriConferenceIQ colibri;

    /**
     * The generation of the candidates we are currently generating
     */
    private int currentGeneration = 0;

    /**
     * The indicator which determines whether this <tt>TransportManager</tt> instance is responsible t0
     * establish the connectivity with the associated Jitsi Videobridge (in case it is being employed at all).
     */
    boolean isEstablishingConnectivityWithJitsiVideobridge = false;

    /**
     * The indicator which determines whether this <tt>TransportManager</tt> instance is yet to
     * start establishing the connectivity with the associated Jitsi Videobridge (in case it is
     * being employed at all).
     */
    boolean startConnectivityEstablishmentWithJitsiVideobridge = false;

    /**
     * Creates a new instance of this transport manager, binding it to the specified peer.
     *
     * @param callPeer the {@link CallPeer} whose traffic we will be taking care of.
     */
    protected TransportManagerJabberImpl(CallPeerJabberImpl callPeer)
    {
        super(callPeer);
    }

    /**
     * Returns the <tt>InetAddress</tt> that is most likely to be to be used as a next hop when
     * contacting the specified <tt>destination</tt>. This is an utility method that is used
     * whenever we have to choose one of our local addresses to put in the Via, Contact or (in the
     * case of no registrar accounts) From headers.
     *
     * @param peer the CallPeer that we would contact.
     * @return the <tt>InetAddress</tt> that is most likely to be to be used as a next hop when
     * contacting the specified <tt>destination</tt>.
     * @throws IllegalArgumentException if <tt>destination</tt> is not a valid host/IP/FQDN
     */
    @Override
    protected InetAddress getIntendedDestination(CallPeerJabberImpl peer)
    {
        return peer.getProtocolProvider().getNextHop();
    }

    /**
     * Returns the ID that we will be assigning to the next candidate we create.
     *
     * @return the next ID to use with a candidate.
     */
    protected String getNextID()
    {
        int nextID;
        synchronized (TransportManagerJabberImpl.class) {
            nextID = TransportManagerJabberImpl.nextID++;
        }
        return Integer.toString(nextID);
    }

    /**
     * Gets the <tt>MediaStreamTarget</tt> to be used as the <tt>target</tt> of the
     * <tt>MediaStream</tt> with a specific <tt>MediaType</tt>.
     *
     * @param mediaType the <tt>MediaType</tt> of the <tt>MediaStream</tt> which is to have its
     * <tt>target</tt> set to the returned <tt>MediaStreamTarget</tt>
     * @return the <tt>MediaStreamTarget</tt> to be used as the <tt>target</tt> of the
     * <tt>MediaStream</tt> with the specified <tt>MediaType</tt>
     */
    public abstract MediaStreamTarget getStreamTarget(MediaType mediaType);

    /**
     * Gets the XML namespace of the Jingle transport implemented by this <tt>TransportManagerJabberImpl</tt>.
     *
     * @return the XML namespace of the Jingle transport implemented by this <tt>TransportManagerJabberImpl</tt>
     */
    public abstract String getXmlNamespace();

    /**
     * Returns the generation that our current candidates belong to.
     *
     * @return the generation that we should assign to candidates that we are currently advertising.
     */
    protected int getCurrentGeneration()
    {
        return currentGeneration;
    }

    /**
     * Increments the generation that we are assigning candidates.
     */
    protected void incrementGeneration()
    {
        currentGeneration++;
    }

    /**
     * Sends transport-related information received from the remote peer to the associated Jiitsi
     * Videobridge in order to update the (remote) <tt>ColibriConferenceIQ.Channel</tt> associated
     * with this <tt>TransportManager</tt> instance.
     *
     * @param map a <tt>Map</tt> of media-IceUdpTransportExtensionElement pairs which represents the
     * transport-related information which has been received from the remote peer and which
     * is to be sent to the associated Jitsi Videobridge
     */
    protected void sendTransportInfoToJitsiVideobridge(Map<String, IceUdpTransportExtension> map)
            throws OperationFailedException
    {
        CallPeerJabberImpl peer = getCallPeer();
        boolean initiator = !peer.isInitiator();
        ColibriConferenceIQ conferenceRequest = null;

        for (Map.Entry<String, IceUdpTransportExtension> e : map.entrySet()) {
            String media = e.getKey();
            MediaType mediaType = MediaType.parseString(media);
            ColibriConferenceIQ.Channel channel = getColibriChannel(mediaType, false /* remote */);

            if (channel != null) {
                IceUdpTransportExtension transport;
                try {
                    transport = cloneTransportAndCandidates(e.getValue());
                } catch (OperationFailedException ofe) {
                    transport = null;
                }
                if (transport == null)
                    continue;

                ColibriConferenceIQ.Channel channelRequest = new ColibriConferenceIQ.Channel();
                channelRequest.setID(channel.getID());
                channelRequest.setInitiator(initiator);
                channelRequest.setTransport(transport);

                if (conferenceRequest == null) {
                    if (colibri == null)
                        break;
                    else {
                        String id = colibri.getID();

                        if ((id == null) || (id.length() == 0))
                            break;
                        else {
                            conferenceRequest = new ColibriConferenceIQ();
                            conferenceRequest.setID(id);
                            conferenceRequest.setTo(colibri.getFrom());
                            conferenceRequest.setType(IQ.Type.set);
                        }
                    }
                }
                conferenceRequest.getOrCreateContent(media).addChannel(channelRequest);
            }
        }
        if (conferenceRequest != null) {
            try {
                peer.getProtocolProvider().getConnection().sendStanza(conferenceRequest);
            } catch (NotConnectedException | InterruptedException e1) {
                throw new OperationFailedException("Could not send conference request",
                        OperationFailedException.GENERAL_ERROR, e1);
            }
        }
    }

    /**
     * Starts transport candidate harvest for a specific <tt>JingleContent</tt> that we are
     * going to offer or answer with.
     *
     * @param theirContent the <tt>JingleContent</tt> offered by the remote peer to which we are going
     * to answer with <tt>ourContent</tt> or <tt>null</tt> if <tt>ourContent</tt> will be an offer to the remote peer
     * @param ourContent the <tt>JingleContent</tt> for which transport candidate harvest is to be started
     * @param transportInfoSender a <tt>TransportInfoSender</tt> if the harvested transport candidates are to be sent in
     * a <tt>transport-info</tt> rather than in <tt>ourContent</tt>; otherwise, <tt>null</tt>
     * @param media the media of the <tt>RtpDescriptionExtensionElement</tt> child of <tt>ourContent</tt>
     * @return a <tt>ExtensionElement</tt> to be added as a child to <tt>ourContent</tt>; otherwise, <tt>null</tt>
     * @throws OperationFailedException if anything goes wrong while starting transport candidate harvest for
     * the specified <tt>ourContent</tt>
     */
    protected abstract ExtensionElement startCandidateHarvest(JingleContent theirContent,
            JingleContent ourContent, TransportInfoSender transportInfoSender, String media)
            throws OperationFailedException;

    /**
     * Starts transport candidate harvest. This method should complete rapidly and, in case of
     * lengthy procedures like STUN/TURN/UPnP candidate harvests are necessary, they should be
     * executed in a separate thread. Candidate harvest would then need to be concluded in the
     * {@link #wrapupCandidateHarvest()} method which would be called once we absolutely need the candidates.
     *
     * @param theirOffer a media description offer that we've received from the remote party
     * and that we should use in case we need to know what transports our peer is using.
     * @param ourAnswer the content descriptions that we should be adding our transport lists to.
     * This is used i.e. when their offer is null, for sending the Jingle session-initiate offer.
     * @param transportInfoSender the <tt>TransportInfoSender</tt> to be used by this
     * <tt>TransportManagerJabberImpl</tt> to send <tt>transport-info</tt> <tt>Jingle</tt>s
     * from the local peer to the remote peer if this <tt>TransportManagerJabberImpl</tt>
     * wishes to utilize <tt>transport-info</tt>. Local candidate addresses sent by this
     * <tt>TransportManagerJabberImpl</tt> in <tt>transport-info</tt> are expected to not be
     * included in the result of {@link #wrapupCandidateHarvest()}.
     * @throws OperationFailedException if we fail to allocate a port number.
     */
    public void startCandidateHarvest(List<JingleContent> theirOffer,
            List<JingleContent> ourAnswer, TransportInfoSender transportInfoSender)
            throws OperationFailedException
    {
        CallPeerJabberImpl peer = getCallPeer();
        CallJabberImpl call = peer.getCall();
        boolean isJitsiVideobridge = call.getConference().isJitsiVideobridge();
        List<JingleContent> cpes = (theirOffer == null) ? ourAnswer : theirOffer;

        /*
         * If Jitsi Videobridge is to be used, determine which channels are to be allocated and
         * attempt to allocate them now.
         */
        if (isJitsiVideobridge) {
            Map<JingleContent, JingleContent> contentMap = new LinkedHashMap<>();
            for (JingleContent cpe : cpes) {
                MediaType mediaType = JingleUtils.getMediaType(cpe);

                /*
                 * The existence of a content for the mediaType and regardless of the existence of
                 * channels in it signals that a channel allocation request has already been sent
                 * for that mediaType.
                 */
                if ((colibri == null) || (colibri.getContent(mediaType.toString()) == null)) {
                    JingleContent local, remote;
                    if (cpes == ourAnswer) {
                        local = cpe;
                        remote = (theirOffer == null) ? null : findContentByName(theirOffer, cpe.getName());
                    }
                    else {
                        local = findContentByName(ourAnswer, cpe.getName());
                        remote = cpe;
                    }
                    contentMap.put(local, remote);
                }
            }
            if (!contentMap.isEmpty()) {
                /*
                 * We are about to request the channel allocations for the media types found in
                 * contentMap. Regardless of the response, we do not want to repeat these requests.
                 */
                if (colibri == null)
                    colibri = new ColibriConferenceIQ();
                for (Map.Entry<JingleContent, JingleContent> e : contentMap.entrySet()) {
                    JingleContent cpe = e.getValue();
                    if (cpe == null)
                        cpe = e.getKey();
                    colibri.getOrCreateContent(JingleUtils.getMediaType(cpe).toString());
                }
                ColibriConferenceIQ conferenceResult = call.createColibriChannels(peer, contentMap);
                if (conferenceResult != null) {
                    String videobridgeID = colibri.getID();
                    String conferenceResultID = conferenceResult.getID();

                    if (videobridgeID == null)
                        colibri.setID(conferenceResultID);
                    else if (!videobridgeID.equals(conferenceResultID))
                        throw new IllegalStateException("conference.id");

                    Jid videobridgeFrom = conferenceResult.getFrom();

                    if ((videobridgeFrom != null) && (videobridgeFrom.length() != 0)) {
                        colibri.setFrom(videobridgeFrom);
                    }

                    for (ColibriConferenceIQ.Content contentResult : conferenceResult.getContents()) {
                        ColibriConferenceIQ.Content content = colibri.getOrCreateContent(contentResult.getName());

                        for (ColibriConferenceIQ.Channel channelResult : contentResult.getChannels()) {
                            if (content.getChannel(channelResult.getID()) == null) {
                                content.addChannel(channelResult);
                            }
                        }
                    }
                }
                else {
                    /*
                     * The call fails if the createColibriChannels method fails which may happen if
                     * the conference packet times out or it can't be built.
                     */
                    ProtocolProviderServiceJabberImpl.throwOperationFailedException(
                            "Failed to allocate colibri channel.", OperationFailedException.GENERAL_ERROR, null);
                }
            }
        }

        for (JingleContent cpe : cpes) {
            String contentName = cpe.getName();
            JingleContent ourContent = findContentByName(ourAnswer, contentName);

            // it might be that we decided not to reply to this content
            if (ourContent != null) {
                JingleContent theirContent = (theirOffer == null)
                        ? null : findContentByName(theirOffer, contentName);
                RtpDescriptionExtension rtpDesc
                        = ourContent.getFirstChildOfType(RtpDescriptionExtension.class);
                String media = rtpDesc.getMedia();
                ExtensionElement pe = startCandidateHarvest(theirContent, ourContent, transportInfoSender, media);

                if (pe != null)
                    ourContent.addChildExtension(pe);
            }
        }
    }

    /**
     * Notifies the transport manager that it should conclude candidate harvesting as soon as
     * possible and return the lists of candidates gathered so far.
     *
     * @return the content list that we received earlier (possibly cloned into a new instance) and
     * that we have updated with transport lists.
     */
    public abstract List<JingleContent> wrapupCandidateHarvest();

    /**
     * Looks through the <tt>cpExtList</tt> and returns the {@link JingleContent} with the specified name.
     *
     * @param cpExtList the list that we will be searching for a specific content.
     * @param name the name of the content element we are looking for.
     * @return the {@link JingleContent} with the specified name or <tt>null</tt> if no
     * such content element exists.
     */
    public static JingleContent findContentByName(Iterable<JingleContent> cpExtList, String name)
    {
        for (JingleContent cpExt : cpExtList) {
            if (cpExt.getName().equals(name))
                return cpExt;
        }
        return null;
    }

    /**
     * Starts the connectivity establishment of this <tt>TransportManagerJabberImpl</tt> i.e. checks
     * the connectivity between the local and the remote peers given the remote counterpart of the
     * negotiation between them.
     *
     * @param remote the collection of <tt>JingleContent</tt>s which represents the remote
     * counterpart of the negotiation between the local and the remote peer
     * @return <tt>true</tt> if connectivity establishment has been started in response to the call;
     * otherwise, <tt>false</tt>. <tt>TransportManagerJabberImpl</tt> implementations which
     * do not perform connectivity checks (e.g. raw UDP) should return <tt>true</tt>. The
     * default implementation does not perform connectivity checks and always returns <tt>true</tt>.
     */
    public boolean startConnectivityEstablishment(Iterable<JingleContent> remote)
            throws OperationFailedException
    {
        return true;
    }

    /**
     * Starts the connectivity establishment of this <tt>TransportManagerJabberImpl</tt> i.e. checks
     * the connectivity between the local and the remote peers given the remote counterpart of the
     * negotiation between them.
     *
     * @param remote a <tt>Map</tt> of media-<tt>IceUdpTransportExtensionElement</tt> pairs which represents
     * the remote counterpart of the negotiation between the local and the remote peers
     * @return <tt>true</tt> if connectivity establishment has been started in response to the call;
     * otherwise, <tt>false</tt>. <tt>TransportManagerJabberImpl</tt> implementations which
     * do not perform connectivity checks (e.g. raw UDP) should return <tt>true</tt>. The
     * default implementation does not perform connectivity checks and always returns <tt>true</tt>.
     */
    protected boolean startConnectivityEstablishment(Map<String, IceUdpTransportExtension> remote)
    {
        return true;
    }

    /**
     * Notifies this <tt>TransportManagerJabberImpl</tt> that it should conclude any started connectivity establishment.
     *
     * @throws OperationFailedException if anything goes wrong with connectivity establishment (i.e. ICE failed, ...)
     */
    public void wrapupConnectivityEstablishment()
            throws OperationFailedException
    {
    }

    /**
     * Removes a content with a specific name from the transport-related part of the session
     * represented by this <tt>TransportManagerJabberImpl</tt> which may have been reported through
     * previous calls to the <tt>startCandidateHarvest</tt> and <tt>startConnectivityEstablishment</tt> methods.
     *
     * <b>Note</b>: Because <tt>TransportManager</tt> deals with <tt>MediaType</tt>s, not content
     * names and <tt>TransportManagerJabberImpl</tt> does not implement translating from content
     * name to <tt>MediaType</tt>, implementers are expected to call
     * {@link TransportManager#closeStreamConnector(MediaType)}.
     *
     * @param name the name of the content to be removed from the transport-related part of the session
     * represented by this <tt>TransportManagerJabberImpl</tt>
     */
    public abstract void removeContent(String name);

    /**
     * Removes a content with a specific name from a specific collection of contents and closes any
     * associated <tt>StreamConnector</tt>.
     *
     * @param contents the collection of contents to remove the content with the specified name from
     * @param name the name of the content to remove
     * @return the removed <tt>JingleContent</tt> if any; otherwise, <tt>null</tt>
     */
    protected JingleContent removeContent(Iterable<JingleContent> contents, String name)
    {
        for (Iterator<JingleContent> contentIter = contents.iterator(); contentIter.hasNext(); ) {
            JingleContent content = contentIter.next();

            if (name.equals(content.getName())) {
                contentIter.remove();

                // closeStreamConnector
                MediaType mediaType = JingleUtils.getMediaType(content);
                if (mediaType != null) {
                    closeStreamConnector(mediaType);
                }
                return content;
            }
        }
        return null;
    }

    /**
     * Clones a specific <tt>IceUdpTransportExtensionElement</tt> and its candidates.
     *
     * @param src the <tt>IceUdpTransportExtensionElement</tt> to be cloned
     * @return a new <tt>IceUdpTransportExtensionElement</tt> instance which has the same run-time
     * type, attributes, namespace, text and candidates as the specified <tt>src</tt>
     * @throws OperationFailedException if an error occurs during the cloing of the specified <tt>src</tt> and its candidates
     */
    static IceUdpTransportExtension cloneTransportAndCandidates(IceUdpTransportExtension src)
            throws OperationFailedException
    {
        try {
            return IceUdpTransportExtension.cloneTransportAndCandidates(src);
        } catch (Exception e) {
            ProtocolProviderServiceJabberImpl.throwOperationFailedException(
                    "Failed to close transport and candidates.", OperationFailedException.GENERAL_ERROR, e);
        }
        return null;
    }

    /**
     * Releases the resources acquired by this <tt>TransportManager</tt> and prepares it for garbage collection.
     */
    public void close()
    {
        for (MediaType mediaType : MediaType.values())
            closeStreamConnector(mediaType);
    }

    /**
     * Closes a specific <tt>StreamConnector</tt> associated with a specific <tt>MediaType</tt>. If
     * this <tt>TransportManager</tt> has a reference to the specified <tt>streamConnector</tt>, it remains.
     * Also expires the <tt>ColibriConferenceIQ.Channel</tt> associated with the closed <tt>StreamConnector</tt>.
     *
     * @param mediaType the <tt>MediaType</tt> associated with the specified <tt>streamConnector</tt>
     * @param streamConnector the <tt>StreamConnector</tt> to be closed
     */
    @Override
    protected void closeStreamConnector(MediaType mediaType, StreamConnector streamConnector)
            throws OperationFailedException
    {
        try {
            boolean superCloseStreamConnector = true;
            if (streamConnector instanceof ColibriStreamConnector) {
                CallPeerJabberImpl peer = getCallPeer();
                if (peer != null) {
                    CallJabberImpl call = peer.getCall();
                    if (call != null) {
                        superCloseStreamConnector = false;
                        call.closeColibriStreamConnector(peer, mediaType, (ColibriStreamConnector) streamConnector);
                    }
                }
            }
            if (superCloseStreamConnector)
                super.closeStreamConnector(mediaType, streamConnector);
        } finally {
            /*
             * Expire the ColibriConferenceIQ.Channel associated with the closed StreamConnector.
             */
            if (colibri != null) {
                ColibriConferenceIQ.Content content = colibri.getContent(mediaType.toString());

                if (content != null) {
                    List<ColibriConferenceIQ.Channel> channels = content.getChannels();

                    if (channels.size() == 2) {
                        ColibriConferenceIQ requestConferenceIQ = new ColibriConferenceIQ();
                        requestConferenceIQ.setID(colibri.getID());
                        ColibriConferenceIQ.Content requestContent
                                = requestConferenceIQ.getOrCreateContent(content.getName());
                        requestContent.addChannel(channels.get(1 /* remote */));

                        /*
                         * Regardless of whether the request to expire the Channel associated with
                         * mediaType succeeds, consider the Channel in question expired. Since
                         * RawUdpTransportManager allocates a single channel per MediaType, consider
                         * the whole Content expired.
                         */
                        colibri.removeContent(content);
                        CallPeerJabberImpl peer = getCallPeer();
                        if (peer != null) {
                            CallJabberImpl call = peer.getCall();
                            if (call != null) {
                                try {
                                    call.expireColibriChannels(peer, requestConferenceIQ);
                                } catch (NotConnectedException | InterruptedException e) {
                                    throw new OperationFailedException("Could not expire colibri channels",
                                            OperationFailedException.GENERAL_ERROR, e);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Adds support for telephony conferences utilizing the Jitsi Videobridge server-side technology.
     *
     * @see #doCreateStreamConnector(MediaType)
     */
    @Override
    protected StreamConnector createStreamConnector(final MediaType mediaType)
            throws OperationFailedException
    {
        ColibriConferenceIQ.Channel channel = getColibriChannel(mediaType, true /* local */);
        if (channel != null) {
            CallPeerJabberImpl peer = getCallPeer();
            CallJabberImpl call = peer.getCall();
            StreamConnector streamConnector = call.createColibriStreamConnector(peer, mediaType, channel, () -> {
                try {
                    return doCreateStreamConnector(mediaType);
                } catch (OperationFailedException ofe) {
                    return null;
                }
            });
            if (streamConnector != null)
                return streamConnector;
        }
        return doCreateStreamConnector(mediaType);
    }

    protected abstract ExtensionElement createTransport(String media)
            throws OperationFailedException;

    protected ExtensionElement createTransportForStartCandidateHarvest(String media)
            throws OperationFailedException
    {
        ExtensionElement pe = null;
        if (getCallPeer().isJitsiVideobridge()) {
            MediaType mediaType = MediaType.parseString(media);
            ColibriConferenceIQ.Channel channel = getColibriChannel(mediaType, false /* remote */);

            if (channel != null)
                pe = cloneTransportAndCandidates(channel.getTransport());
        }
        else
            pe = createTransport(media);
        return pe;
    }

    /**
     * Initializes a new <tt>ExtensionElement</tt> instance appropriate to the type of Jingle
     * transport represented by this <tt>TransportManager</tt>. The new instance is not initialized
     * with any attributes or child extensions.
     *
     * @return a new <tt>ExtensionElement</tt> instance appropriate to the type of Jingle transport
     * represented by this <tt>TransportManager</tt>
     */
    protected abstract ExtensionElement createTransportPacketExtension();

    /**
     * Creates a media <tt>StreamConnector</tt> for a stream of a specific <tt>MediaType</tt>. The
     * minimum and maximum of the media port boundaries are taken into account.
     *
     * @param mediaType the <tt>MediaType</tt> of the stream for which a <tt>StreamConnector</tt> is to be created
     * @return a <tt>StreamConnector</tt> for the stream of the specified <tt>mediaType</tt>
     * @throws OperationFailedException if the binding of the sockets fails
     */
    protected StreamConnector doCreateStreamConnector(MediaType mediaType)
            throws OperationFailedException
    {
        return super.createStreamConnector(mediaType);
    }

    /**
     * Finds a <tt>TransportManagerJabberImpl</tt> participating in a telephony conference utilizing
     * the Jitsi Videobridge server-side technology that this instance is participating in which is
     * establishing the connectivity with the Jitsi Videobridge server (as opposed to a <tt>CallPeer</tt>).
     *
     * @return a <tt>TransportManagerJabberImpl</tt> which is participating in a telephony
     * conference utilizing the Jitsi Videobridge server-side technology that this instance
     * is participating in which is establishing the connectivity with the Jitsi Videobridge
     * server (as opposed to a <tt>CallPeer</tt>).
     */
    TransportManagerJabberImpl findTransportManagerEstablishingConnectivityWithJitsiVideobridge()
    {
        Call call = getCallPeer().getCall();
        TransportManagerJabberImpl transportManager = null;

        if (call != null) {
            CallConference conference = call.getConference();
            if ((conference != null) && conference.isJitsiVideobridge()) {
                for (Call aCall : conference.getCalls()) {
                    Iterator<? extends CallPeer> callPeerIter = aCall.getCallPeers();

                    while (callPeerIter.hasNext()) {
                        CallPeer aCallPeer = callPeerIter.next();
                        if (aCallPeer instanceof CallPeerJabberImpl) {
                            TransportManagerJabberImpl aTransportManager
                                    = ((CallPeerJabberImpl) aCallPeer).getMediaHandler().getTransportManager();

                            if (aTransportManager.isEstablishingConnectivityWithJitsiVideobridge) {
                                transportManager = aTransportManager;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return transportManager;
    }

    /**
     * Gets the {@link ColibriConferenceIQ.Channel} which belongs to a content associated with a
     * specific <tt>MediaType</tt> and is to be either locally or remotely used.
     *
     * <b>Note</b>: Modifications to the <tt>ColibriConferenceIQ.Channel</tt> instance returned by
     * the method propagate to (the state of) this instance.
     *
     * @param mediaType the <tt>MediaType</tt> associated with the content which contains the
     * <tt>ColibriConferenceIQ.Channel</tt> to get
     * @param local <tt>true</tt> if the <tt>ColibriConferenceIQ.Channel</tt> which is to be used locally
     * is to be returned or <tt>false</tt> for the one which is to be used remotely
     * @return the <tt>ColibriConferenceIQ.Channel</tt> which belongs to a content associated with
     * the specified <tt>mediaType</tt> and which is to be used in accord with the specified
     * <tt>local</tt> indicator if such a channel exists; otherwise, <tt>null</tt>
     */
    ColibriConferenceIQ.Channel getColibriChannel(MediaType mediaType, boolean local)
    {
        ColibriConferenceIQ.Channel channel = null;
        if (colibri != null) {
            ColibriConferenceIQ.Content content = colibri.getContent(mediaType.toString());
            if (content != null) {
                List<ColibriConferenceIQ.Channel> channels = content.getChannels();
                if (channels.size() == 2)
                    channel = channels.get(local ? 0 : 1);
            }
        }
        return channel;
    }

    /**
     * Sets the flag which indicates whether to use rtcpmux or not.
     */
    public abstract void setRtcpmux(boolean rtcpmux);

    public boolean isRtcpmux()
    {
        return false;
    }
}
