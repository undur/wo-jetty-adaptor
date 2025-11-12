package com.webobjects.appserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
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
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver._private.WOInputStreamData;
import com.webobjects.appserver._private.WONoCopyPushbackInputStream;
import com.webobjects.appserver._private.WOProperties;
import com.webobjects.appserver.websocket.WOJettyWebSocketListener;
import com.webobjects.appserver.websocket.WOWebSocketHandler;
import com.webobjects.appserver.websocket.WOWebSocketRegistry;
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

	/**
	 * Invoked by WO to construct an adaptor instance
	 */
	public WOAdaptorJetty( String name, NSDictionary<String, Object> config ) {
		super( name, config );
		_port = port( config );

		// If the port is occupied, we emulate WO's behaviour. Helps ERXApplication handle the exception, restarting any app occupying the port
		if( !isPortAvailable( _port ) ) {
			throw new NSForwardException( new BindException( "Port %s is occupied".formatted( _port ) ) );
		}

		try {
			// Copied from the Netty adaptor
			WOApplication.application()._setHost( InetAddress.getLocalHost().getHostName() );
			System.setProperty( WOProperties._PortKey, Integer.toString( _port ) );
		}
		catch( UnknownHostException e ) {
			throw new RuntimeException( e );
		}
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
		final HttpConnectionFactory connectionFactory = new HttpConnectionFactory( config );

		final ServerConnector connector = new ServerConnector( server, connectionFactory );
		connector.setPort( _port );
		server.addConnector( connector );

		// Create the main HTTP handler
		final WOHandler woHandler = new WOHandler();

		// Wrap with WebSocket upgrade capability
		final Handler handler = createWebSocketHandler( server, woHandler );
		server.setHandler( handler );

		try {
			logger.info( "Starting %s on port %s".formatted( getClass().getSimpleName(), _port ) );
			if( WOWebSocketRegistry.registeredEndpointCount() > 0 ) {
				logger.info( "WebSocket support enabled with {} registered endpoint(s)", WOWebSocketRegistry.registeredEndpointCount() );
			}
			server.start();
		}
		catch( final Exception e ) {
			e.printStackTrace();
			System.exit( -1 );
		}
	}

	/**
	 * Creates a handler that supports both HTTP and WebSocket requests.
	 * WebSocket upgrade requests will be handled by the WebSocket infrastructure,
	 * all other requests will be handled by the WO handler.
	 */
	private Handler createWebSocketHandler( final Server server, final WOHandler woHandler ) {
		// Get the WebSocket container from the server
		final ServerWebSocketContainer container = ServerWebSocketContainer.ensure( server );

		// Create the upgrade handler that intercepts WebSocket upgrade requests
		return new WebSocketUpgradeHandler( container ) {
			@Override
			public boolean handle( Request request, Response response, Callback callback ) throws Exception {
				// Check if this is a WebSocket upgrade request for a registered path
				final String path = request.getHttpURI().getPath();

				if( WOWebSocketRegistry.hasHandlerForPath( path ) && isWebSocketUpgrade( request ) ) {
					// Let the WebSocket infrastructure handle the upgrade
					logger.debug( "WebSocket upgrade request for path: {}", path );

					// Create a handler instance for this connection
					final WOWebSocketHandler handler = WOWebSocketRegistry.createHandlerInstance( path, WOApplication.application() );

					if( handler != null ) {
						// Convert the Jetty request to a WORequest so we can pass it to the handler
						final WORequest woRequest = WOHandler.requestToWORequest( request );

						// Create the WebSocket creator that returns our listener
						WebSocketCreator creator = new WebSocketCreator() {
							@Override
							public Object createWebSocket( org.eclipse.jetty.websocket.server.ServerUpgradeRequest req,
									org.eclipse.jetty.websocket.server.ServerUpgradeResponse resp, Callback cb ) {
								return new WOJettyWebSocketListener( handler, woRequest );
							}
						};

						// Perform the WebSocket upgrade
						if( container.upgrade( creator, request, response, callback ) ) {
							return true;
						}
					}
				}

				// Not a WebSocket upgrade, handle as normal HTTP request
				return woHandler.handle( request, response, callback );
			}

			private boolean isWebSocketUpgrade( Request request ) {
				final String upgrade = request.getHeaders().get( "Upgrade" );
				final String connection = request.getHeaders().get( "Connection" );
				return "websocket".equalsIgnoreCase( upgrade ) &&
						connection != null && connection.toLowerCase().contains( "upgrade" );
			}
		};
	}

	public static class WOHandler extends Handler.Abstract {

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

			//			for( final WOCookie woCookie : woResponse.cookies() ) {
			//				Response.addCookie( jettyResponse, woCookieToJettyCookie( woCookie ) );
			//			}

			for( final Entry<String, NSArray<String>> entry : woResponse.headers().entrySet() ) {
				jettyResponse.getHeaders().add( entry.getKey(), entry.getValue() );
			}

			if( woResponse.contentInputStream() != null ) {
				final long contentLength = woResponse.contentInputStreamLength(); // If an InputStream is present, the stream's length must be present as well

				if( contentLength == -1 ) {
					throw new IllegalArgumentException( "WOResponse.contentInputStream() is set but contentInputLength has not been set. You must provide the content length when serving an InputStream" );
				}

				jettyResponse.getHeaders().put( "content-length", String.valueOf( contentLength ) );

				final Content.Source cs = Content.Source.from( woResponse.contentInputStream() );
				Content.copy( cs, jettyResponse, callback );
			}
			else {
				try( final OutputStream out = Content.Sink.asOutputStream( jettyResponse )) {
					final long contentLength = woResponse.content()._bytesNoCopy().length;
					jettyResponse.getHeaders().put( "content-length", String.valueOf( contentLength ) );
					new ByteArrayInputStream( woResponse.content()._bytesNoCopy() ).transferTo( out );
					callback.succeeded();
				}
			}
		}

		/**
		 * @return the given Request converted to a WORequest
		 */
		private static WORequest requestToWORequest( final Request jettyRequest ) {

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

			//			for( final HttpCookie jettyCookie : Request.getCookies( jettyRequest ) ) {
			//				worequest.addCookie( jettyCookieToWOCookie( jettyCookie ) );
			//			}

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

	//		private static WOCookie jettyCookieToWOCookie( final HttpCookie jettyCookie ) {
	//			return new WOCookie(
	//					jettyCookie.getName(),
	//					jettyCookie.getValue(),
	//					jettyCookie.getPath(),
	//					jettyCookie.getDomain(),
	//					(int)jettyCookie.getMaxAge(),
	//					jettyCookie.isSecure(),
	//					jettyCookie.isHttpOnly() );
	//		}
	//
	//		private static HttpCookie woCookieToJettyCookie( final WOCookie woCookie ) {
	//			final HttpCookie.Builder jettyCookieBuilder = HttpCookie.build( woCookie.name(), woCookie.value() );
	//
	//			if( woCookie.domain() != null ) {
	//				jettyCookieBuilder.domain( woCookie.domain() );
	//			}
	//
	//			if( woCookie.path() != null ) {
	//				jettyCookieBuilder.path( woCookie.path() );
	//			}
	//
	//			jettyCookieBuilder.httpOnly( woCookie.isHttpOnly() );
	//			jettyCookieBuilder.secure( woCookie.isSecure() );
	//
	//			// FIXME: The cookie's timeout might not always be set. In that case, we probably need to read this from the expires (or set the expires header) // Hugi 2025-11-11
	//			jettyCookieBuilder.maxAge( woCookie.timeOut() );
	//
	//			if( woCookie.sameSite() != null ) {
	//				try {
	//					jettyCookieBuilder.sameSite( SameSite.from( woCookie.sameSite().name() ) );
	//				}
	//				catch( Exception e ) {
	//					logger.error( "Unknown samesite: " + woCookie.sameSite() + " : " + e.getMessage() );
	//				}
	//			}
	//
	//			return jettyCookieBuilder.build();
	//		}

}