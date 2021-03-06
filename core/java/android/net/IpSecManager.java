/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.net;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.SystemApi;
import android.content.Context;
import android.os.INetworkManagementService;
import android.os.ParcelFileDescriptor;
import android.util.AndroidException;
import dalvik.system.CloseGuard;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;

/**
 * This class contains methods for managing IPsec sessions, which will perform kernel-space
 * encryption and decryption of socket or Network traffic.
 *
 * <p>An IpSecManager may be obtained by calling {@link
 * android.content.Context#getSystemService(String) Context#getSystemService(String)} with {@link
 * android.content.Context#IPSEC_SERVICE Context#IPSEC_SERVICE}
 */
public final class IpSecManager {
    private static final String TAG = "IpSecManager";

    /**
     * Indicates that the combination of remote InetAddress and SPI was non-unique for a given
     * request. If encountered, selection of a new SPI is required before a transform may be
     * created. Note, this should happen very rarely if the SPI is chosen to be sufficiently random
     * or reserved using reserveSecurityParameterIndex.
     */
    public static final class SpiUnavailableException extends AndroidException {
        private final int mSpi;

        /**
         * Construct an exception indicating that a transform with the given SPI is already in use
         * or otherwise unavailable.
         *
         * @param msg Description indicating the colliding SPI
         * @param spi the SPI that could not be used due to a collision
         */
        SpiUnavailableException(String msg, int spi) {
            super(msg + "(spi: " + spi + ")");
            mSpi = spi;
        }

        /** Retrieve the SPI that caused a collision */
        public int getSpi() {
            return mSpi;
        }
    }

    /**
     * Indicates that the requested system resource for IPsec, such as a socket or other system
     * resource is unavailable. If this exception is thrown, try releasing allocated objects of the
     * type requested.
     */
    public static final class ResourceUnavailableException extends AndroidException {

        ResourceUnavailableException(String msg) {
            super(msg);
        }
    }

    private final Context mContext;
    private final INetworkManagementService mService;

    public static final class SecurityParameterIndex implements AutoCloseable {
        private final Context mContext;
        private final InetAddress mDestinationAddress;
        private final CloseGuard mCloseGuard = CloseGuard.get();
        private int mSpi;

        /** Return the underlying SPI held by this object */
        public int getSpi() {
            return mSpi;
        }

        private SecurityParameterIndex(Context context, InetAddress destinationAddress, int spi)
                throws ResourceUnavailableException, SpiUnavailableException {
            mContext = context;
            mDestinationAddress = destinationAddress;
            mSpi = spi;
            mCloseGuard.open("open");
        }

        /**
         * Release an SPI that was previously reserved.
         *
         * <p>Release an SPI for use by other users in the system. This will fail if the SPI is
         * currently in use by an IpSecTransform.
         *
         * @param destinationAddress SPIs must be unique for each combination of SPI and destination
         *     address. Thus, the destinationAddress to which the SPI will communicate must be
         *     supplied.
         * @param spi the previously reserved SPI to be freed.
         */
        @Override
        public void close() {
            mSpi = INVALID_SECURITY_PARAMETER_INDEX; // TODO: Invalid SPI
            mCloseGuard.close();
        }

        @Override
        protected void finalize() {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        }
    }

    /**
     * The Security Parameter Index, SPI, 0 indicates an unknown or invalid index.
     *
     * <p>No IPsec packet may contain an SPI of 0.
     */
    public static final int INVALID_SECURITY_PARAMETER_INDEX = 0;

    /**
     * Reserve an SPI for traffic bound towards the specified destination address.
     *
     * <p>If successful, this SPI is guaranteed available until released by a call to {@link
     * SecurityParameterIndex#close()}.
     *
     * @param destinationAddress SPIs must be unique for each combination of SPI and destination
     *     address.
     * @param requestedSpi the requested SPI, or '0' to allocate a random SPI.
     * @return the reserved SecurityParameterIndex
     * @throws ResourceUnavailableException indicating that too many SPIs are currently allocated
     *     for this user
     * @throws SpiUnavailableException indicating that a particular SPI cannot be reserved
     */
    public SecurityParameterIndex reserveSecurityParameterIndex(
            InetAddress destinationAddress, int requestedSpi)
            throws SpiUnavailableException, ResourceUnavailableException {
        return new SecurityParameterIndex(mContext, destinationAddress, requestedSpi);
    }

    /**
     * Apply an active Transport Mode IPsec Transform to a stream socket to perform IPsec
     * encapsulation of the traffic flowing between the socket and the remote InetAddress of that
     * transform. For security reasons, attempts to send traffic to any IP address other than the
     * address associated with that transform will throw an IOException. In addition, if the
     * IpSecTransform is later deactivated, the socket will throw an IOException on any calls to
     * send() or receive() until the transform is removed from the socket by calling {@link
     * #removeTransportModeTransform(Socket, IpSecTransform)};
     *
     * @param socket a stream socket
     * @param transform an {@link IpSecTransform}, which must be an active Transport Mode transform.
     */
    public void applyTransportModeTransform(Socket socket, IpSecTransform transform)
            throws IOException {
        applyTransportModeTransform(ParcelFileDescriptor.fromSocket(socket), transform);
    }

    /**
     * Apply an active Transport Mode IPsec Transform to a datagram socket to perform IPsec
     * encapsulation of the traffic flowing between the socket and the remote InetAddress of that
     * transform. For security reasons, attempts to send traffic to any IP address other than the
     * address associated with that transform will throw an IOException. In addition, if the
     * IpSecTransform is later deactivated, the socket will throw an IOException on any calls to
     * send() or receive() until the transform is removed from the socket by calling {@link
     * #removeTransportModeTransform(DatagramSocket, IpSecTransform)};
     *
     * @param socket a datagram socket
     * @param transform an {@link IpSecTransform}, which must be an active Transport Mode transform.
     */
    public void applyTransportModeTransform(DatagramSocket socket, IpSecTransform transform)
            throws IOException {
        applyTransportModeTransform(ParcelFileDescriptor.fromDatagramSocket(socket), transform);
    }

    /* Call down to activate a transform */
    private void applyTransportModeTransform(ParcelFileDescriptor pfd, IpSecTransform transform) {}

    /**
     * Apply an active Tunnel Mode IPsec Transform to a network, which will tunnel all traffic to
     * and from that network's interface with IPsec (applies an outer IP header and IPsec Header to
     * all traffic, and expects an additional IP header and IPsec Header on all inbound traffic).
     * Applications should probably not use this API directly. Instead, they should use {@link
     * VpnService} to provide VPN capability in a more generic fashion.
     *
     * @param net a {@link Network} that will be tunneled via IP Sec.
     * @param transform an {@link IpSecTransform}, which must be an active Tunnel Mode transform.
     * @hide
     */
    @SystemApi
    public void applyTunnelModeTransform(Network net, IpSecTransform transform) {}

    /**
     * Remove a transform from a given stream socket. Once removed, traffic on the socket will not
     * be encypted. This allows sockets that have been used for IPsec to be reclaimed for
     * communication in the clear in the event socket reuse is desired. This operation will succeed
     * regardless of the underlying state of a transform. If a transform is removed, communication
     * on all sockets to which that transform was applied will fail until this method is called.
     *
     * @param socket a socket that previously had a transform applied to it.
     * @param transform the IPsec Transform that was previously applied to the given socket
     */
    public void removeTransportModeTransform(Socket socket, IpSecTransform transform) {
        removeTransportModeTransform(ParcelFileDescriptor.fromSocket(socket), transform);
    }

    /**
     * Remove a transform from a given datagram socket. Once removed, traffic on the socket will not
     * be encypted. This allows sockets that have been used for IPsec to be reclaimed for
     * communication in the clear in the event socket reuse is desired. This operation will succeed
     * regardless of the underlying state of a transform. If a transform is removed, communication
     * on all sockets to which that transform was applied will fail until this method is called.
     *
     * @param socket a socket that previously had a transform applied to it.
     * @param transform the IPsec Transform that was previously applied to the given socket
     */
    public void removeTransportModeTransform(DatagramSocket socket, IpSecTransform transform) {
        removeTransportModeTransform(ParcelFileDescriptor.fromDatagramSocket(socket), transform);
    }

    /* Call down to activate a transform */
    private void removeTransportModeTransform(ParcelFileDescriptor pfd, IpSecTransform transform) {}

    /**
     * Remove a Tunnel Mode IPsec Transform from a {@link Network}. This must be used as part of
     * cleanup if a tunneled Network experiences a change in default route. The Network will drop
     * all traffic that cannot be routed to the Tunnel's outbound interface. If that interface is
     * lost, all traffic will drop.
     *
     * @param net a network that currently has transform applied to it.
     * @param transform a Tunnel Mode IPsec Transform that has been previously applied to the given
     *     network
     * @hide
     */
    @SystemApi
    public void removeTunnelModeTransform(Network net, IpSecTransform transform) {}

    /**
     * Class providing access to a system-provided UDP Encapsulation Socket, which may be used for
     * IKE signalling as well as for inbound and outbound UDP encapsulated IPsec traffic.
     *
     * <p>The socket provided by this class cannot be re-bound or closed via the inner
     * FileDescriptor. Instead, disposing of this socket requires a call to close().
     */
    public static final class UdpEncapsulationSocket implements AutoCloseable {
        private final FileDescriptor mFd;
        private final Context mContext;
        private final CloseGuard mCloseGuard = CloseGuard.get();

        private UdpEncapsulationSocket(Context context, int port)
                throws ResourceUnavailableException {
            mContext = context;
            mCloseGuard.open("constructor");
            // TODO: go down to the kernel and get a socket on the specified
            mFd = new FileDescriptor();
        }

        private UdpEncapsulationSocket(Context context) throws ResourceUnavailableException {
            mContext = context;
            mCloseGuard.open("constructor");
            // TODO: go get a random socket on a random port
            mFd = new FileDescriptor();
        }

        /** Access the inner UDP Encapsulation Socket */
        public FileDescriptor getSocket() {
            return mFd;
        }

        /** Retrieve the port number of the inner encapsulation socket */
        public int getPort() {
            return 0; // TODO get the port number from the Socket;
        }

        @Override
        /**
         * Release the resources that have been reserved for this Socket.
         *
         * <p>This method closes the underlying socket, reducing a user's allocated sockets in the
         * system. This must be done as part of cleanup following use of a socket. Failure to do so
         * will cause the socket to count against a total allocation limit for IpSec and eventually
         * fail due to resource limits.
         *
         * @param fd a file descriptor previously returned as a UDP Encapsulation socket.
         */
        public void close() {
            // TODO: Go close the socket
            mCloseGuard.close();
        }

        @Override
        protected void finalize() throws Throwable {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        }
    };

    /**
     * Open a socket that is bound to a free UDP port on the system.
     *
     * <p>By binding in this manner and holding the FileDescriptor, the socket cannot be un-bound by
     * the caller. This provides safe access to a socket on a port that can later be used as a UDP
     * Encapsulation port.
     *
     * <p>This socket reservation works in conjunction with IpSecTransforms, which may re-use the
     * socket port. Explicitly opening this port is only necessary if communication is desired on
     * that port.
     *
     * @param port a local UDP port to be reserved for UDP Encapsulation. is provided, then this
     *     method will bind to the specified port or fail. To retrieve the port number, call {@link
     *     android.system.Os#getsockname(FileDescriptor)}.
     * @return a {@link UdpEncapsulationSocket} that is bound to the requested port for the lifetime
     *     of the object.
     */
    // Returning a socket in this fashion that has been created and bound by the system
    // is the only safe way to ensure that a socket is both accessible to the user and
    // safely usable for Encapsulation without allowing a user to possibly unbind from/close
    // the port, which could potentially impact the traffic of the next user who binds to that
    // socket.
    public UdpEncapsulationSocket openUdpEncapsulationSocket(int port)
            throws IOException, ResourceUnavailableException {
        // Temporary code
        return new UdpEncapsulationSocket(mContext, port);
    }

    /**
     * Open a socket that is bound to a port selected by the system.
     *
     * <p>By binding in this manner and holding the FileDescriptor, the socket cannot be un-bound by
     * the caller. This provides safe access to a socket on a port that can later be used as a UDP
     * Encapsulation port.
     *
     * <p>This socket reservation works in conjunction with IpSecTransforms, which may re-use the
     * socket port. Explicitly opening this port is only necessary if communication is desired on
     * that port.
     *
     * @return a {@link UdpEncapsulationSocket} that is bound to an arbitrarily selected port
     */
    // Returning a socket in this fashion that has been created and bound by the system
    // is the only safe way to ensure that a socket is both accessible to the user and
    // safely usable for Encapsulation without allowing a user to possibly unbind from/close
    // the port, which could potentially impact the traffic of the next user who binds to that
    // socket.
    public UdpEncapsulationSocket openUdpEncapsulationSocket()
            throws IOException, ResourceUnavailableException {
        // Temporary code
        return new UdpEncapsulationSocket(mContext);
    }

    /**
     * Retrieve an instance of an IpSecManager within you application context
     *
     * @param context the application context for this manager
     * @hide
     */
    public IpSecManager(Context context, INetworkManagementService service) {
        mContext = checkNotNull(context, "missing context");
        mService = checkNotNull(service, "missing service");
    }
}
