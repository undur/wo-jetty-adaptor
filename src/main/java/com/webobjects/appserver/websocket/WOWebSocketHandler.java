package com.webobjects.appserver.websocket;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WORequest;

/**
 * Base class for WebSocket handlers in WebObjects applications.
 * Subclass this to handle WebSocket connections at specific endpoints.
 *
 * Example:
 * <pre>
 * public class ChatHandler extends WOWebSocketHandler {
 *     {@literal @}Override
 *     public void onConnect(WOWebSocketSession session) {
 *         logger.info("Client connected: {}", session.getRemoteAddress());
 *     }
 *
 *     {@literal @}Override
 *     public void onTextMessage(WOWebSocketSession session, String message) {
 *         // Echo the message back
 *         try {
 *             session.sendText("Echo: " + message);
 *         } catch (IOException e) {
 *             logger.error("Failed to send message", e);
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class WOWebSocketHandler {

	/**
	 * Logger for this handler. Subclasses can use this for logging.
	 */
	protected final Logger logger = LoggerFactory.getLogger( getClass() );

	/**
	 * The WOApplication instance that this handler is associated with
	 */
	private WOApplication _application;

	/**
	 * Called when a new WebSocket connection is established.
	 * Override this to perform initialization when a client connects.
	 *
	 * @param session the WebSocket session for this connection
	 * @param request the initial HTTP request that initiated the WebSocket upgrade
	 */
	public void onConnect( WOWebSocketSession session, WORequest request ) {}

	/**
	 * Called when a text message is received from the client.
	 *
	 * @param session the WebSocket session
	 * @param message the text message received
	 */
	public void onTextMessage( WOWebSocketSession session, String message ) {}

	/**
	 * Called when a binary message is received from the client.
	 *
	 * @param session the WebSocket session
	 * @param data the binary data received
	 */
	public void onBinaryMessage( WOWebSocketSession session, ByteBuffer data ) {}

	/**
	 * Called when the WebSocket connection is closed.
	 *
	 * @param session the WebSocket session
	 * @param statusCode the close status code
	 * @param reason the reason for closing
	 */
	public void onClose( WOWebSocketSession session, int statusCode, String reason ) {}

	/**
	 * Called when an error occurs on the WebSocket connection.
	 *
	 * @param session the WebSocket session
	 * @param cause the error that occurred
	 */
	public void onError( WOWebSocketSession session, Throwable cause ) {
		logger.error( "WebSocket error", cause );
	}

	/**
	 * Set the WOApplication instance. Called internally by the adaptor.
	 *
	 * @param application the application instance
	 */
	public final void _setApplication( WOApplication application ) {
		this._application = application;
	}

	/**
	 * @return the WOApplication instance
	 */
	public final WOApplication application() {
		return _application;
	}
}