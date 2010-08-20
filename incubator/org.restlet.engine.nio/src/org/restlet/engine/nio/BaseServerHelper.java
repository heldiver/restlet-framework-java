/**
 * Copyright 2005-2010 Noelios Technologies.
 * 
 * The contents of this file are subject to the terms of one of the following
 * open source licenses: LGPL 3.0 or LGPL 2.1 or CDDL 1.0 or EPL 1.0 (the
 * "Licenses"). You can select the license that you prefer but you may not use
 * this file except in compliance with one of these Licenses.
 * 
 * You can obtain a copy of the LGPL 3.0 license at
 * http://www.opensource.org/licenses/lgpl-3.0.html
 * 
 * You can obtain a copy of the LGPL 2.1 license at
 * http://www.opensource.org/licenses/lgpl-2.1.php
 * 
 * You can obtain a copy of the CDDL 1.0 license at
 * http://www.opensource.org/licenses/cddl1.php
 * 
 * You can obtain a copy of the EPL 1.0 license at
 * http://www.opensource.org/licenses/eclipse-1.0.php
 * 
 * See the Licenses for the specific language governing permissions and
 * limitations under the Licenses.
 * 
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly at
 * http://www.noelios.com/products/restlet-engine
 * 
 * Restlet is a registered trademark of Noelios Technologies.
 */

package org.restlet.engine.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Server;

/**
 * Base server helper based on NIO non blocking sockets. Here is the list of
 * parameters that are supported. They should be set in the Server's context
 * before it is started:
 * <table>
 * <tr>
 * <th>Parameter name</th>
 * <th>Value type</th>
 * <th>Default value</th>
 * <th>Description</th>
 * </tr>
 * <td>useForwardedForHeader</td>
 * <td>boolean</td>
 * <td>false</td>
 * <td>Lookup the "X-Forwarded-For" header supported by popular proxies and
 * caches and uses it to populate the Request.getClientAddresses() method
 * result. This information is only safe for intermediary components within your
 * local network. Other addresses could easily be changed by setting a fake
 * header and should not be trusted for serious security checks.</td>
 * </tr>
 * </table>
 * 
 * @author Jerome Louvel
 */
public class BaseServerHelper extends BaseHelper<Server> {

    /** The server socket channel. */
    private volatile ServerSocketChannel serverSocketChannel;

    /**
     * Constructor.
     * 
     * @param server
     *            The server to help.
     */
    public BaseServerHelper(Server server) {
        super(server, false);

        // Clear the ephemeral port
        getAttributes().put("ephemeralPort", -1);
    }

    @Override
    protected Connection<Server> createConnection(BaseHelper<Server> helper,
            SocketChannel socketChannel, Selector selector) throws IOException {
        return new Connection<Server>(helper, socketChannel, selector);
    }

    @Override
    protected ServerController createController() {
        return new ServerController(this);
    }

    @Override
    public ServerInboundWay createInboundWay(Connection<Server> connection) {
        return new ServerInboundWay(connection);
    }

    @Override
    public OutboundWay createOutboundWay(Connection<Server> connection) {
        return new ServerOutboundWay(connection);
    }

    /**
     * Creates a new request.
     * 
     * @param connection
     *            The associated connection.
     * @param methodName
     *            The method name.
     * @param resourceUri
     *            The target resource URI.
     * @param version
     *            The protocol version.
     * @return The created request.
     */
    protected ConnectedRequest createRequest(Connection<Server> connection,
            String methodName, String resourceUri, String version) {
        return new ConnectedRequest(getContext(), connection, methodName,
                resourceUri, version);
    }

    /**
     * Create a server socket channel and bind it to the given address
     * 
     * @return Bound server socket channel.
     * @throws IOException
     */
    protected ServerSocketChannel createServerSocketChannel()
            throws IOException {
        ServerSocketChannel result = ServerSocketChannel.open();
        result.socket().setReuseAddress(true);
        result.socket().setSoTimeout(getMaxIoIdleTimeMs());
        result.socket().bind(createSocketAddress());
        result.configureBlocking(false);
        return result;
    }

    /**
     * Creates a socket address to listen on.
     * 
     * @return The created socket address.
     * @throws IOException
     */
    protected SocketAddress createSocketAddress() throws IOException {
        if (getHelped().getAddress() == null) {
            return new InetSocketAddress(getHelped().getPort());
        }

        return new InetSocketAddress(getHelped().getAddress(), getHelped()
                .getPort());
    }

    @Override
    public ServerController getController() {
        return (ServerController) super.getController();
    }

    /**
     * Returns the server socket channel.
     * 
     * @return The server socket channel.
     */
    public ServerSocketChannel getServerSocketChannel() {
        return serverSocketChannel;
    }

    /**
     * Handles a call by invoking the helped Server's
     * {@link Server#handle(Request, Response)} method.
     * 
     * @param request
     *            The request to handle.
     * @param response
     *            The response to update.
     */
    @Override
    public void handle(Request request, Response response) {
        super.handle(request, response);
        getHelped().handle(request, response);
    }

    @Override
    public void handleInbound(Response response) {
        if ((response != null) && (response.getRequest() != null)) {
            getLogger().fine("Handling inbound message");
            ConnectedRequest request = (ConnectedRequest) response.getRequest();

            // Effectively handle the request
            handle(request, response);

            if (!response.isCommitted() && response.isAutoCommitting()) {
                getOutboundMessages().add(response);
                response.setCommitted(true);
                getController().wakeup();
            }
        }

        handleNextOutbound();
    }

    @Override
    public void handleOutbound(Response response) {
        if (response != null) {
            getLogger().fine("Handling outbound message");
            ConnectedRequest request = (ConnectedRequest) response.getRequest();
            Connection<Server> connection = request.getConnection();

            if (request.isExpectingResponse()) {
                // Check if the response is indeed the next one to be written
                // for this connection
                Response nextResponse = connection.getInboundWay()
                        .getMessages().peek();

                if ((nextResponse != null)
                        && (nextResponse.getRequest() == request)) {
                    // Add the response to the outbound queue
                    connection.getOutboundWay().getMessages().add(response);
                } else {
                    // Put the response at the end of the queue
                    getOutboundMessages().add(response);
                    getController().wakeup();
                }
            } else {
                // The request expects no response, the connection is free to
                // read a new request.
            }
        }
    }

    /**
     * Indicates if the controller thread should be a daemon (not blocking JVM
     * exit).
     * 
     * @return True if the controller thread should be a daemon (not blocking
     *         JVM exit).
     */
    public boolean isControllerDaemon() {
        return Boolean.parseBoolean(getHelpedParameters().getFirstValue(
                "controllerDaemon", "false"));
    }

    /**
     * Sets the ephemeral port in the attributes map if necessary.
     * 
     * @param localPort
     *            The ephemeral local port.
     */
    public void setEphemeralPort(int localPort) {
        // If an ephemeral port is used, make sure we update the attribute for
        // the API
        if (getHelped().getPort() == 0) {
            getAttributes().put("ephemeralPort", localPort);
        }
    }

    /**
     * Sets the ephemeral port in the attributes map if necessary.
     * 
     * @param socket
     *            The bound server socket.
     */
    public void setEphemeralPort(ServerSocket socket) {
        setEphemeralPort(socket.getLocalPort());
    }

    @Override
    public synchronized void start() throws Exception {
        // Create the server socket channel
        this.serverSocketChannel = createServerSocketChannel();

        // Sets the ephemeral port is necessary
        setEphemeralPort(this.serverSocketChannel.socket());

        // Start the controller
        super.start();

        // Wait for the listener to start up and count down the latch
        // This blocks until the server is ready to receive connections
        try {
            getController().await();
        } catch (InterruptedException ex) {
            getLogger()
                    .log(Level.WARNING,
                            "Interrupted while waiting for starting latch. Stopping...",
                            ex);
            stop();
        }
    }

    @Override
    public synchronized void stop() throws Exception {
        super.stop();

        // Close the server socket channel
        if (getServerSocketChannel() != null) {
            getServerSocketChannel().close();
        }

        // Clear the ephemeral port
        getAttributes().put("ephemeralPort", -1);
    }
}