package com.webobjects.appserver.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.websocket.api.Session;

/**
 * Jetty-specific implementation of WOWebSocketSession.
 * Wraps a Jetty WebSocket Session and provides the WO API.
 */
public class WOJettyWebSocketSession implements WOWebSocketSession {

	private final Session jettySession;
	private final Map<String, Object> attributes = new ConcurrentHashMap<>();

	public WOJettyWebSocketSession( Session jettySession ) {
		this.jettySession = jettySession;
	}

	@Override
	public boolean isOpen() {
		return jettySession != null && jettySession.isOpen();
	}

	@Override
	public void sendText( String message ) throws IOException {
		if( jettySession != null && jettySession.isOpen() ) {
			jettySession.sendText( message, null );
		}
		else {
			throw new IOException( "WebSocket session is not open" );
		}
	}

	@Override
	public void sendBinary( ByteBuffer data ) throws IOException {
		if( jettySession != null && jettySession.isOpen() ) {
			jettySession.sendBinary( data, null );
		}
		else {
			throw new IOException( "WebSocket session is not open" );
		}
	}

	@Override
	public void close() throws IOException {
		if( jettySession != null && jettySession.isOpen() ) {
			jettySession.close();
		}
	}

	@Override
	public void close( int statusCode, String reason ) throws IOException {
		if( jettySession != null && jettySession.isOpen() ) {
			jettySession.close( statusCode, reason, null );
		}
	}

	@Override
	public String getRemoteAddress() {
		if( jettySession != null && jettySession.getRemoteSocketAddress() != null ) {
			return jettySession.getRemoteSocketAddress().toString();
		}
		return "unknown";
	}

	@Override
	public Object getAttribute( String key ) {
		return attributes.get( key );
	}

	@Override
	public void setAttribute( String key, Object value ) {
		attributes.put( key, value );
	}

	@Override
	public void removeAttribute( String key ) {
		attributes.remove( key );
	}

	/**
	 * @return the underlying Jetty session
	 */
	Session getJettySession() {
		return jettySession;
	}
}