/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.atalk.impl.neomedia.device;

import org.atalk.android.plugin.timberlog.TimberLog;
import java.awt.Dimension;
import org.atalk.impl.neomedia.*;
import org.atalk.impl.neomedia.format.MediaFormatImpl;
import org.atalk.impl.neomedia.jmfext.media.protocol.AbstractPullBufferCaptureDevice;
import org.atalk.impl.neomedia.protocol.CaptureDeviceDelegatePushBufferDataSource;
import org.atalk.service.neomedia.*;
import org.atalk.service.neomedia.codec.EncodingConfiguration;
import org.atalk.service.neomedia.device.MediaDevice;
import org.atalk.service.neomedia.device.ScreenDevice;
import org.atalk.service.neomedia.format.MediaFormat;
import org.atalk.util.MediaType;

import java.io.IOException;
import java.util.*;

import javax.media.*;
import javax.media.control.FormatControl;
import javax.media.protocol.*;

import timber.log.Timber;

/**
 * Implements <tt>MediaDevice</tt> for the JMF <tt>CaptureDevice</tt>.
 *
 * @author Lyubomir Marinov
 * @author Emil Ivov
 * @author Eng Chong Meng
 */
public class MediaDeviceImpl extends AbstractMediaDevice
{
    /**
     * Creates a new <tt>CaptureDevice</tt> which traces calls to a specific <tt>CaptureDevice</tt>
     * for debugging purposes.
     *
     * @param captureDevice the <tt>CaptureDevice</tt> which is to have its calls traced for debugging output
     * @return a new <tt>CaptureDevice</tt> which traces the calls to the specified <tt>captureDevice</tt>
     */
    public static CaptureDevice createTracingCaptureDevice(CaptureDevice captureDevice)
    {
        if (captureDevice instanceof PushBufferDataSource)
            captureDevice = new CaptureDeviceDelegatePushBufferDataSource(captureDevice)
            {
                @Override
                public void connect()
                        throws IOException
                {
                    super.connect();
                    Timber.log(TimberLog.FINER, "Connected %s", MediaDeviceImpl.toString(this.captureDevice));
                }

                @Override
                public void disconnect()
                {
                    super.disconnect();
                    Timber.log(TimberLog.FINER, "Disconnected %s", MediaDeviceImpl.toString(this.captureDevice));
                }

                @Override
                public void start()
                        throws IOException
                {
                    super.start();
                    Timber.log(TimberLog.FINER, "Started %s", MediaDeviceImpl.toString(this.captureDevice));
                }

                @Override
                public void stop()
                        throws IOException
                {
                    super.stop();
                    Timber.log(TimberLog.FINER, "Stopped %s", MediaDeviceImpl.toString(this.captureDevice));
                }
            };
        return captureDevice;
    }

    /**
     * Returns a human-readable representation of a specific <tt>CaptureDevice</tt> in the form of a <tt>String</tt> value.
     *
     * @param captureDevice the <tt>CaptureDevice</tt> to get a human-readable representation of
     * @return a <tt>String</tt> value which gives a human-readable representation of the specified <tt>captureDevice</tt>
     */
    private static String toString(CaptureDevice captureDevice)
    {
        StringBuilder str = new StringBuilder();

        str.append("CaptureDevice with hashCode ");
        str.append(captureDevice.hashCode());
        str.append(" and captureDeviceInfo ");

        CaptureDeviceInfo captureDeviceInfo = captureDevice.getCaptureDeviceInfo();
        MediaLocator mediaLocator = null;
        if (captureDeviceInfo != null) {
            mediaLocator = captureDeviceInfo.getLocator();
        }
        str.append((mediaLocator == null) ? captureDeviceInfo : mediaLocator);
        return str.toString();
    }

    /**
     * The <tt>CaptureDeviceInfo</tt> of the device that this instance is representing.
     */
    private final CaptureDeviceInfo captureDeviceInfo;

    /**
     * The <tt>MediaType</tt> of this instance and the <tt>CaptureDevice</tt> that it wraps.
     */
    private final MediaType mediaType;

    /**
     * Initializes a new <tt>MediaDeviceImpl</tt> instance which is to provide an implementation of
     * <tt>MediaDevice</tt> for a <tt>CaptureDevice</tt> with a specific <tt>CaptureDeviceInfo</tt>
     * and which is of a specific <tt>MediaType</tt>.
     *
     * @param captureDeviceInfo the <tt>CaptureDeviceInfo</tt> of the JMF <tt>CaptureDevice</tt> the new instance is
     * to provide an implementation of <tt>MediaDevice</tt> for
     * @param mediaType the <tt>MediaType</tt> of the new instance
     */
    public MediaDeviceImpl(CaptureDeviceInfo captureDeviceInfo, MediaType mediaType)
    {
        if (captureDeviceInfo == null)
            throw new NullPointerException("captureDeviceInfo");
        if (mediaType == null)
            throw new NullPointerException("mediaType");

        this.captureDeviceInfo = captureDeviceInfo;
        this.mediaType = mediaType;
    }

    /**
     * Initializes a new <tt>MediaDeviceImpl</tt> instance with a specific <tt>MediaType</tt> and
     * with <tt>MediaDirection</tt> which does not allow sending.
     *
     * @param mediaType the <tt>MediaType</tt> of the new instance
     */
    public MediaDeviceImpl(MediaType mediaType)
    {
        this.captureDeviceInfo = null;
        this.mediaType = mediaType;
    }

    /**
     * Creates the JMF <tt>CaptureDevice</tt> this instance represents and provides an
     * implementation of <tt>MediaDevice</tt> for.
     *
     * @return the JMF <tt>CaptureDevice</tt> this instance represents and provides an
     * implementation of <tt>MediaDevice</tt> for; <tt>null</tt> if the creation fails
     */
    protected CaptureDevice createCaptureDevice()
    {
        CaptureDevice captureDevice = null;
        if (getDirection().allowsSending()) {
            CaptureDeviceInfo captureDeviceInfo = getCaptureDeviceInfo();
            Throwable exception = null;

            try {
                captureDevice = (CaptureDevice) Manager.createDataSource(captureDeviceInfo.getLocator());
            } catch (IOException | NoDataSourceException ioe) {
                exception = ioe;
            }

            if (exception != null) {
                Timber.e(exception, "Failed to create CaptureDevice from CaptureDeviceInfo %s",
                        captureDeviceInfo);
            }
            else {
                if (captureDevice instanceof AbstractPullBufferCaptureDevice) {
                    ((AbstractPullBufferCaptureDevice) captureDevice).setCaptureDeviceInfo(captureDeviceInfo);
                }
                // Try to enable tracing on captureDevice.
                captureDevice = createTracingCaptureDevice(captureDevice);
            }
        }
        return captureDevice;
    }

    /**
     * Creates a <tt>DataSource</tt> instance for this <tt>MediaDevice</tt> which gives access to the captured media.
     *
     * @return a <tt>DataSource</tt> instance which gives access to the media captured by this <tt>MediaDevice</tt>
     * @see AbstractMediaDevice#createOutputDataSource()
     */
    @Override
    protected DataSource createOutputDataSource()
    {
        return getDirection().allowsSending() ? (DataSource) createCaptureDevice() : null;
    }

    /**
     * Gets the <tt>CaptureDeviceInfo</tt> of the JMF <tt>CaptureDevice</tt> represented by this instance.
     *
     * @return the <tt>CaptureDeviceInfo</tt> of the <tt>CaptureDevice</tt> represented by this instance
     */
    public CaptureDeviceInfo getCaptureDeviceInfo()
    {
        return captureDeviceInfo;
    }

    /**
     * Gets the protocol of the <tt>MediaLocator</tt> of the <tt>CaptureDeviceInfo</tt> represented by this instance.
     *
     * @return the protocol of the <tt>MediaLocator</tt> of the <tt>CaptureDeviceInfo</tt> represented by this instance
     */
    public String getCaptureDeviceInfoLocatorProtocol()
    {
        CaptureDeviceInfo cdi = getCaptureDeviceInfo();
        if (cdi != null) {
            MediaLocator locator = cdi.getLocator();

            if (locator != null)
                return locator.getProtocol();
        }
        return null;
    }

    /**
     * Returns the <tt>MediaDirection</tt> supported by this device.
     *
     * @return {@link MediaDirection#SENDONLY} if this is a read-only device,
     * {@link MediaDirection#RECVONLY} if this is a write-only device or
     * {@link MediaDirection#SENDRECV} if this <tt>MediaDevice</tt> can both capture and render media
     * @see MediaDevice#getDirection()
     */
    public MediaDirection getDirection()
    {
        if (getCaptureDeviceInfo() != null)
            return MediaDirection.SENDRECV;
        else
            return MediaType.AUDIO.equals(getMediaType()) ? MediaDirection.INACTIVE : MediaDirection.RECVONLY;
    }

    /**
     * Gets the <tt>MediaFormat</tt> in which this <tt>MediaDevice</tt> captures media.
     *
     * @return the <tt>MediaFormat</tt> in which this <tt>MediaDevice</tt> captures media
     * @see MediaDevice#getFormat()
     */
    public MediaFormat getFormat()
    {
        CaptureDevice captureDevice = createCaptureDevice();
        if (captureDevice != null) {
            MediaType mediaType = getMediaType();

            for (FormatControl formatControl : captureDevice.getFormatControls()) {
                MediaFormat format = MediaFormatImpl.createInstance(formatControl.getFormat());

                if ((format != null) && format.getMediaType().equals(mediaType))
                    return format;
            }
        }
        return null;
    }

    /**
     * Gets the <tt>MediaType</tt> that this device supports.
     *
     * @return {@link MediaType#AUDIO} if this is an audio device or {@link MediaType#VIDEO} if this is a video device
     * @see MediaDevice#getMediaType()
     */
    public MediaType getMediaType()
    {
        return mediaType;
    }

    /**
     * Gets the list of <tt>MediaFormat</tt>s supported by this <tt>MediaDevice</tt> and enabled in <tt>encodingConfiguration</tt>.
     *
     * @param encodingConfiguration the <tt>EncodingConfiguration</tt> instance to use
     * @return the list of <tt>MediaFormat</tt>s supported by this device and enabled in <tt>encodingConfiguration</tt>.
     * @see MediaDevice#getSupportedFormats()
     */
    public List<MediaFormat> getSupportedFormats(EncodingConfiguration encodingConfiguration)
    {
        return getSupportedFormats(null, null, encodingConfiguration);
    }

    /**
     * Gets the list of <tt>MediaFormat</tt>s supported by this <tt>MediaDevice</tt>. Uses the
     * current <tt>EncodingConfiguration</tt> from the media service (i.e. the global configuration).
     *
     * @param sendPreset the preset used to set some of the format parameters, used for video and settings.
     * @param receivePreset the preset used to set the receive format parameters, used for video and settings.
     * @return the list of <tt>MediaFormat</tt>s supported by this device
     * @see MediaDevice#getSupportedFormats()
     */
    public List<MediaFormat> getSupportedFormats(QualityPreset sendPreset, QualityPreset receivePreset)
    {
        return getSupportedFormats(sendPreset, receivePreset,
                NeomediaServiceUtils.getMediaServiceImpl().getCurrentEncodingConfiguration());
    }

    /**
     * Gets the list of <tt>MediaFormat</tt>s supported by this <tt>MediaDevice</tt> and enabled in
     * <tt>encodingConfiguration</tt>.
     *
     * @param sendPreset the preset used to set some of the format parameters, used for video and settings.
     * @param receivePreset the preset used to set the receive format parameters, used for video and settings.
     * @param encodingConfiguration the <tt>EncodingConfiguration</tt> instance to use
     * @return the list of <tt>MediaFormat</tt>s supported by this device and enabled in <tt>encodingConfiguration</tt>.
     * @see MediaDevice#getSupportedFormats()
     */
    public List<MediaFormat> getSupportedFormats(QualityPreset sendPreset,
            QualityPreset receivePreset, EncodingConfiguration encodingConfiguration)
    {
        MediaServiceImpl mediaServiceImpl = NeomediaServiceUtils.getMediaServiceImpl();
        MediaFormat[] enabledEncodings = encodingConfiguration.getEnabledEncodings(getMediaType());
        List<MediaFormat> supportedFormats = new ArrayList<MediaFormat>();

        // If there is preset, check and set the format attributes where needed.
        if (enabledEncodings != null) {
            for (MediaFormat f : enabledEncodings) {
                if ("h264".equalsIgnoreCase(f.getEncoding())) {
                    Map<String, String> advancedAttrs = f.getAdvancedAttributes();

                    CaptureDeviceInfo captureDeviceInfo = getCaptureDeviceInfo();
                    MediaLocator captureDeviceInfoLocator;
                    Dimension sendSize = null;

                    // change send size only for video calls
                    if ((captureDeviceInfo != null)
                            && ((captureDeviceInfoLocator = captureDeviceInfo.getLocator()) != null)
                            && !DeviceSystem.LOCATOR_PROTOCOL_IMGSTREAMING
                            .equals(captureDeviceInfoLocator.getProtocol())) {
                        if (sendPreset != null)
                            sendSize = sendPreset.getResolution();
                        else {
                            /*
                             * XXX We cannot default to any video size here because we do not know
                             * how this MediaDevice instance will be used. If the caller wanted to
                             * limit the video size, she would've specified an actual sendPreset.
                             */
                            // sendSize = mediaServiceImpl.getDeviceConfiguration().getVideoSize();
                        }
                    }
                    Dimension receiveSize;
                    // if there is specified preset, send its settings
                    if (receivePreset != null)
                        receiveSize = receivePreset.getResolution();
                    else {
                        // or just send the max video resolution of the PC as we do by default
                        ScreenDevice screen = mediaServiceImpl.getDefaultScreenDevice();
                        receiveSize = (screen == null) ? null : screen.getSize();
                    }

                    advancedAttrs.put("imageattr",
                            MediaUtils.createImageAttr(sendSize, receiveSize));
                    f = mediaServiceImpl.getFormatFactory().createMediaFormat(f.getEncoding(),
                            f.getClockRate(), f.getFormatParameters(), advancedAttrs);
                }
                if (f != null)
                    supportedFormats.add(f);
            }
        }
        return supportedFormats;
    }

    /**
     * Gets a human-readable <tt>String</tt> representation of this instance.
     *
     * @return a <tt>String</tt> providing a human-readable representation of this instance
     */
    @Override
    public String toString()
    {
        CaptureDeviceInfo captureDeviceInfo = getCaptureDeviceInfo();
        return (captureDeviceInfo == null) ? super.toString() : captureDeviceInfo.toString();
    }
}
