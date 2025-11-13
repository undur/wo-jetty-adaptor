package com.webobjects.appserver;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver._private.WOInputStreamData;
import com.webobjects.appserver._private.WONoCopyPushbackInputStream;
import com.webobjects.appserver._private.WOProperties;
import com.webobjects.appserver.websocket.WOJettyWebSocketSupport;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSForwardException;

/**
 * A WOAdaptor based on Jetty.
 *
 * To use, set the property -WOAdaptor WOJettyAdaptor
 */

public class WOAdaptorJetty extends WOAdaptor {

	private static final Logger logger = LoggerFactory.getLogger( WOAdaptorJetty.class );

	// FIXME: This should eventually be configurable // Hugi 2025-11-13
	private static final boolean ENABLE_WEBSOCKETS = true;

	/**
	 * Invoked by WO to construct an adaptor instance
	 */
	public WOAdaptorJetty( String name, NSDictionary<String, Object> config ) throws UnknownHostException {
		super( name, config );
		_port = port( config );

		// If the port is occupied, we emulate WO's behaviour. Helps ERXApplication handle the exception, restarting any app occupying the port
		if( !isPortAvailable( _port ) ) {
			throw new NSForwardException( new BindException( "Port %s is occupied".formatted( _port ) ) );
		}

		// Copied from the Netty adaptor
		WOApplication.application()._setHost( InetAddress.getLocalHost().getHostName() );
		System.setProperty( WOProperties._PortKey, Integer.toString( _port ) );
	}

	/**
	 * @return the port we'll be listening on
	 */
	private static int port( NSDictionary<String, Object> config ) {
		final Number number = (Number)config.objectForKey( WOProperties._PortKey );

		int port = 0;

		if( number != null ) {
			port = number.intValue();
		}

		if( port < 0 ) {
			port = 0;
		}

		return port;
	}

	/**
	 * @return true if the given port is available for us to use
	 */
	private static boolean isPortAvailable( int port ) {
		try( ServerSocket socket = new ServerSocket( port )) {
			return true;
		}
		catch( IOException e ) {
			return false;
		}
	}

	@Override
	public boolean dispatchesRequestsConcurrently() {
		return true;
	}

	@Override
	public void unregisterForEvents() {
		// FIXME: Missing implementation // Hugi 2025-11-11
		logger.error( "We haven't implemented WOAdaptor.unregisterForEvents()" );
	}

	@Override
	public void registerForEvents() {

		final Server server = new Server();

		final HttpConfiguration config = new HttpConfiguration();
		// Configure output buffer sizes for optimal performance
		// outputBufferSize: Size of buffer for aggregating response content (default: 32KB)
		// outputAggregationSize: Max size of writes copied into buffer (default: 8KB)
		// Larger buffers improve throughput but increase memory usage and may add latency
		config.setOutputBufferSize( 32768 ); // 32 KB - good balance for most responses
		config.setOutputAggregationSize( 8192 ); // 8 KB - aggregate small writes efficiently

		final HttpConnectionFactory connectionFactory = new HttpConnectionFactory( config );

		final ServerConnector connector = new ServerConnector( server, connectionFactory );
		connector.setPort( _port );
		server.addConnector( connector );

		Handler handler = new WOJettyHandler();

		// If websockets are enabled, we wrap the handler with WS upgrade capabilities
		if( ENABLE_WEBSOCKETS ) {
			handler = WOJettyWebSocketSupport.createWebSocketHandler( server, handler );
		}

		server.setHandler( handler );

		try {
			logger.info( "Starting %s on port %s".formatted( getClass().getSimpleName(), _port ) );
			server.start();
		}
		catch( final Exception e ) {
			e.printStackTrace();
			System.exit( -1 );
		}
	}

	public static class WOJettyHandler extends Handler.Abstract {

		@Override
		public boolean handle( Request request, Response response, Callback callback ) throws Exception {
			doRequest( request, response, callback );
			return true;
		}

		private void doRequest( final Request jettyRequest, final Response jettyResponse, Callback callback ) throws IOException {

			final WORequest woRequest = requestToWORequest( jettyRequest );

			// This is where the application logic will perform it's actual work
			final WOResponse woResponse = WOApplication.application().dispatchRequest( woRequest );

			jettyResponse.setStatus( woResponse.status() );

			for( final Entry<String, NSArray<String>> entry : woResponse.headers().entrySet() ) {
				jettyResponse.getHeaders().add( entry.getKey(), entry.getValue() );
			}

			if( woResponse.contentInputStream() != null ) {
				final long contentLength = woResponse.contentInputStreamLength(); // If an InputStream is present, the stream's length must be present as well

				if( contentLength == -1 ) {
					throw new IllegalArgumentException( "WOResponse.contentInputStream() is set but contentInputLength has not been set. You must provide the content length when serving an InputStream" );
				}

				jettyResponse.getHeaders().put( "content-length", String.valueOf( contentLength ) );

				// Content.Source.from() handles buffering internally via ByteBufferPool
				// No need to wrap in BufferedInputStream (would cause double-buffering)
				final Content.Source cs = Content.Source.from( woResponse.contentInputStream() );
				Content.copy( cs, jettyResponse, callback );
			}
			else {
				final NSData responseContent = woResponse.content();

				jettyResponse.getHeaders().put( "content-length", String.valueOf( responseContent.length() ) );

				try( final OutputStream out = Response.asBufferedOutputStream( jettyRequest, jettyResponse )) {
					responseContent.writeToStream( out );
				}

				callback.succeeded();
			}
		}

		/**
		 * @return the given Request converted to a WORequest
		 */
		public static WORequest requestToWORequest( final Request jettyRequest ) {

			final ConnectionMetaData md = jettyRequest.getConnectionMetaData();

			final String method = jettyRequest.getMethod();
			final String uri = jettyRequest.getHttpURI().getPathQuery();
			final String httpVersion = md.getHttpVersion().asString();
			final Map<String, List<String>> headers = headerMap( jettyRequest );

			final NSData contentData;

			final int length = (int)jettyRequest.getLength();

			if( length > 0 ) {
				logger.info( "Constructing streaming request content with length: " + length );

				// All of this stream wrapping is required for WO to be happy. Yay!
				final InputStream jettyStream = Request.asInputStream( jettyRequest );
				final InputStream bufferedStream = new BufferedInputStream( jettyStream );
				final WONoCopyPushbackInputStream wrappedStream = new WONoCopyPushbackInputStream( bufferedStream, length );
				contentData = new WOInputStreamData( wrappedStream, length );
			}
			else {
				contentData = NSData.EmptyData;
			}

			final WORequest worequest = WOApplication.application().createRequest( method, uri, httpVersion, headers, contentData, null );

			populateAddresses( md, worequest );

			return worequest;
		}

		/**
		 * Populate origin data in the WORequest
		 */
		private static void populateAddresses( final ConnectionMetaData md, final WORequest worequest ) {

			if( md.getRemoteSocketAddress() instanceof InetSocketAddress remote ) {
				worequest._setOriginatingAddress( remote.getAddress() );
				worequest._setOriginatingPort( remote.getPort() );
			}

			if( md.getLocalSocketAddress() instanceof InetSocketAddress local ) {
				worequest._setAcceptingAddress( local.getAddress() );
				worequest._setAcceptingPort( local.getPort() );
			}
		}

		/**
		 * @return The headers from the Request as a Map
		 */
		private static Map<String, List<String>> headerMap( final Request jettyRequest ) {
			final Map<String, List<String>> map = new HashMap<>();

			for( final HttpField httpField : jettyRequest.getHeaders() ) {
				map.put( httpField.getName(), httpField.getValueList() );
			}

			return map;
		}
	}
}