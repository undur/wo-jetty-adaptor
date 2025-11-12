package com.webobjects.appserver.websocket;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WORequest;

/**
 * Jetty WebSocket listener that delegates to a WOWebSocketHandler.
 * This bridges Jetty's WebSocket API to WebObjects' WebSocket API.
 */
public class WOJettyWebSocketListener implements Session.Listener {

	private static final Logger logger = LoggerFactory.getLogger( WOJettyWebSocketListener.class );

	private final WOWebSocketHandler handler;
	private final WORequest initialRequest;
	private WOJettyWebSocketSession woSession;

	public WOJettyWebSocketListener( WOWebSocketHandler handler, WORequest initialRequest ) {
		this.handler = handler;
		this.initialRequest = initialRequest;
	}

	@Override
	public void onWebSocketOpen( Session session ) {
		woSession = new WOJettyWebSocketSession( session );
		try {
			handler.onConnect( woSession, initialRequest );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onConnect handler", e );
			handler.onError( woSession, e );
		}
		// Start demanding messages
		session.demand();
	}

	@Override
	public void onWebSocketText( String message ) {
		try {
			handler.onTextMessage( woSession, message );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onTextMessage handler", e );
			handler.onError( woSession, e );
		}
		// Demand more data for the next message
		if( woSession != null && woSession.isOpen() ) {
			woSession.getJettySession().demand();
		}
	}

	@Override
	public void onWebSocketBinary( ByteBuffer payload, Callback callback ) {
		try {
			handler.onBinaryMessage( woSession, payload );
			callback.succeed();
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onBinaryMessage handler", e );
			handler.onError( woSession, e );
			callback.fail( e );
		}
	}

	@Override
	public void onWebSocketClose( int statusCode, String reason ) {
		try {
			handler.onClose( woSession, statusCode, reason );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onClose handler", e );
		}
	}

	@Override
	public void onWebSocketError( Throwable cause ) {
		try {
			handler.onError( woSession, cause );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onError handler", e );
		}
	}
}