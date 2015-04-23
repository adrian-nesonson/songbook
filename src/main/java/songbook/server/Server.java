package songbook.server;

import io.undertow.Undertow;
import io.undertow.io.Sender;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.handlers.ExceptionHandler;
import io.undertow.server.handlers.PathTemplateHandler;
import io.undertow.util.*;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import songbook.song.IndexDatabase;
import songbook.song.SongDatabase;
import songbook.song.SongUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.undertow.Handlers.*;

public class Server {

	public final static int DEFAULT_PORT = 8080;

	public final static String DEFAULT_HOST = "localhost";

	public final static String DEFAULT_WEB_ROOT = "web";

	public final static String DEFAULT_DATA_ROOT = "data";

	public static final String ADMINISTRATOR_KEY_PATH = "administrator.key";
	public static final String ADMINISTRATOR_ACTIVATED_PATH = "administrator.activated";

	public static final String MIME_TEXT_HTML = "text/html";
	public static final String MIME_TEXT_PLAIN = "text/plain";
	public static final String MIME_TEXT_SONG = "text/song";
	public static final String SESSION_KEY = "SessionKey";

	public static final AttachmentKey<String> ADMIN_KEY = AttachmentKey.create(String.class);

	private Logger logger;

	private int port = DEFAULT_PORT;

	private SongDatabase songDb;

	private IndexDatabase indexDb;

	private boolean showKeyCreationAlert = false;
	private String administratorKey = null;
	private String userKey = null;

	public void start() {
		logger = Logger.getLogger("Songbook");

		Path dataRoot = getDataRoot();
		Templates.setTemplatesPath(getWebRoot().resolve("templates"));

		try {
			if (Files.exists(dataRoot) == false) Files.createDirectories(dataRoot);
		} catch (IOException e) {
			error("Cannot start server data root isn't accessible.", e);
			return;
		}

		readKeys();

		// creates admin key if needed
		if (administratorKey == null) createAdminKey();

		// initialize songDb
		songDb = new SongDatabase(getSongsPath());

		// initializes index.
		try {
			indexDb = new IndexDatabase(getDataRoot().resolve("index"), songDb);
		} catch (IOException e) {
			error("Can't initialize index in " + dataRoot.resolve("index"), e);
		}

		// creates server
		Undertow undertow = createServer(pathTemplateHandler());

		final int port = getPort();
		final String host = getHost();
		info("Starting server on '" + host + ":" + port + "'.");
		undertow.start();
	}

	private PathTemplateHandler pathTemplateHandler() {
		PathTemplateHandler pathHandler = pathTemplate();

		pathHandler.add("/", get(this::search)); // Home Page

		pathHandler.add("/view/:id", get(this::getSong));
		pathHandler.add("/edit/:id", adminAccess(get(this::editSong)));
		pathHandler.add("/delete/:id", adminAccess(get(this::deleteSong)));
		pathHandler.add("/new", adminAccess(get(this::editSong)));


		pathHandler.add("/search/:query", get(this::search));
		pathHandler.add("/search/", get(this::search));
		pathHandler.add("/search", get(this::search));

		pathHandler.add("/songs", adminAccess(post(this::createSong)));
		pathHandler.add("/songs/", adminAccess(post(this::createSong)));

		pathHandler.add("/songs/:id", get(this::getSong));
		pathHandler.add("/songs/:id", adminAccess(put(this::modifySong)));
		pathHandler.add("/songs/:id", adminAccess(delete(this::deleteSong)));

		pathHandler.add("/consoleApi", get(this::consoleApi));

		pathHandler.add("/signin", get(this::signin));
		pathHandler.add("/admin/:section/:command", adminAccess(get(this::adminCommand)));
		pathHandler.add("/admin", adminAccess(get(this::admin)));

		pathHandler.add("*", get(this::serveFile));

		return pathHandler;
	}

	private void serveFile(final HttpServerExchange exchange) throws IOException {
		// TODO use ResourceManager and resource handler

		// Serve Files
		String path = exchange.getRequestPath().equals("/") ? "index.html" : exchange.getRequestPath().substring(1);
		Path localFilePath = getWebRoot().resolve(path).toAbsolutePath();
		logger.info("GET " + localFilePath);
		String type = "text/plain";
		if (path.endsWith(".js")) {
			type = "application/javascript";
		} else if (path.endsWith(".css")) {
			type = "text/css";
		} else if (path.endsWith(".html")) {
			type = "text/html";
		}

		exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, type);
		send(exchange, () -> {
			try {
				return Files.newByteChannel(localFilePath);
			} catch (IOException e) {
				error("Can't read file '" + path + "'", e);
				return null;
			}
		});
	}

	private void search(final HttpServerExchange exchange) throws Exception {
		// Serve all songs
		String query = getParameter(exchange, "query");
		String title = "My SongBook";
		if (query != null && !query.isEmpty()) {
			title = query + " - " + title;
		}

		StringBuilder out = new StringBuilder();
		String mimeType = MimeParser.bestMatch(getHeader(exchange, Headers.ACCEPT), MIME_TEXT_SONG, MIME_TEXT_PLAIN, MIME_TEXT_HTML);
		switch (mimeType) {
			case MIME_TEXT_HTML:
				exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");

				Templates.header(out, title, getRole(exchange));
				if (showKeyCreationAlert) {
					Templates.alertKeyCreation(out, administratorKey, exchange.getRequestPath());
				}
				indexDb.search(query, out, mimeType);
				Templates.footer(out);
				break;
			default:
				indexDb.search(query, out, mimeType);
				break;
		}
		exchange.getResponseSender().send(out.toString());
	}

	private String sessionKey(final HttpServerExchange exchange) {
		// TODO handle session inside a handler

		String sessionKey = null;
		Map<String, Cookie> cookies = Cookies.parseRequestCookies(10, false, exchange.getRequestHeaders().get(Headers.COOKIE));
		for (Cookie cookie : cookies.values()) {
			if (SESSION_KEY.equals(cookie.getName())) {
				sessionKey = cookie.getValue();
			}
		}

		String key = getParameter(exchange, "key");
		if (key != null && !key.isEmpty() && !key.equals(sessionKey)) {
			sessionKey = key;
			// Set Cookie
			Cookie cookie = new CookieImpl(SESSION_KEY, sessionKey);
			cookie.setMaxAge(Integer.MAX_VALUE);
			exchange.getResponseHeaders().put(Headers.SET_COOKIE, cookie.getValue());
		}
		if (isAdministrator(sessionKey)) {
			// gets administrator key, remove alert (if present)
			if (showKeyCreationAlert) {
				showKeyCreationAlert = false;
				try {
					Files.createFile(getDataRoot().resolve(ADMINISTRATOR_ACTIVATED_PATH));
				} catch (IOException e) {
					error("Can't create file '" + ADMINISTRATOR_ACTIVATED_PATH + "'", e);
				}
			}
		}
		return sessionKey;
	}

	private void getSong(final HttpServerExchange exchange) throws Exception {
		String id = getParameter(exchange, ("id"));

		// Serves song
		String songContents = songDb.getSongContents(id);
		if (songContents == null) throw new SongNotFoundException(id);

		String mimeType = MimeParser.bestMatch(getHeader(exchange, Headers.ACCEPT), MIME_TEXT_SONG, MIME_TEXT_PLAIN, MIME_TEXT_HTML);
		exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, mimeType);
		switch (mimeType) {
			case MIME_TEXT_HTML:
				exchange.getResponseSender().send(htmlSong(exchange, id, songContents, exchange.getRequestPath()));
				break;
			default:
			case MIME_TEXT_PLAIN:
			case MIME_TEXT_SONG:
				exchange.getResponseSender().send(songContents);
				break;
		}

		logger.info("Serve Song " + id);
	}

	private String htmlSong(HttpServerExchange exchange, String id, String songData, String path) {
		StringBuilder out = new StringBuilder();
		// Todo use a songmark object to extract title and then generate html
		String title = SongUtils.getTitle(songData);
		Templates.header(out, title + " - My SongBook", getRole(exchange));
		if (showKeyCreationAlert) Templates.alertKeyCreation(out, administratorKey, path);
		Templates.viewSong(out, id, SongUtils.writeHtml(new StringBuilder(), songData));

		Templates.footer(out);
		return out.toString();
	}

	private void editSong(final HttpServerExchange exchange) throws Exception{
		String id = getParameter(exchange, ("id"));

		// Serves song
		exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");

		StringBuilder out = new StringBuilder();
		String role = getRole(exchange);

		if (id != null && !id.isEmpty()) {

			String songContents = songDb.getSongContents(id);
			if (songContents == null) throw new SongNotFoundException(id);
			String title = SongUtils.getTitle(songContents);
			Templates.header(out, "Edit - " + title + " - My SongBook", role);
			Templates.editSong(out, id, songContents);
			Templates.footer(out);

			exchange.getResponseSender().send(out.toString());

		} else {
			Templates.header(out, "Create Song - My SongBook", role);
			Templates.editSong(out, "", Templates.newSong(new StringBuilder()));
			Templates.footer(out);
			exchange.getResponseSender().send(out.toString());
		}
	}

	private void createSong(final HttpServerExchange exchange) {
		request.bodyHandler((body) -> {
			HttpServerResponse response = request.response();
			String songData = body.toString();
			try {

				// indexes updated song
				Document document = SongUtils.indexSong(songData);
				String title = document.get("title");
				String artist = document.get("artist");

				if (title == null || title.isEmpty() || artist == null) {
					response.setStatusCode(400);
					response.end("You must provide a title and an artist information", "UTF-8");
					return;
				}
				String id = songDb.generateId(title, artist);
				// prepares new document
				document.add(new StringField("id", id, Field.Store.YES));
				indexDb.addOrUpdateDocument(document);

				// writes song to database
				songDb.writeSong(id, songData, (ar) -> {
					if (ar.succeeded()) {
						response.end(id, "UTF-8");
					} else {
						error("Failed to create the song", ar.cause());
						response.setStatusCode(500);
						response.end();
					}
				});


			} catch (Exception e) {
				error("Error indexing song", e);
				response.setStatusCode(500);
				response.end();
			}
		});
	}

	private void modifySong(final HttpServerExchange exchange) {
		request.bodyHandler((body) -> {
			HttpServerResponse response = request.response();
			String songData = body.toString();
			try {

				// indexes updated song
				Document document = SongUtils.indexSong(songData);

				String id = getParameter(exchange, ("id"));

				// Verify that song exists
				if (songDb.exists(id)) {
					// prepares new document
					document.add(new StringField("id", id, Field.Store.YES));
					indexDb.addOrUpdateDocument(document);

					// writes song to database
					songDb.writeSong(id, songData, (ar) -> {
						if (ar.succeeded()) {
							response.end(id, "UTF-8");
						} else {
							error("Failed to update the song", ar.cause());
							response.setStatusCode(500);
							response.end();
						}
					});
				} else {
					response.setStatusCode(400);
					response.end("The song doesn't exist and cannot be updated", "UTF-8");
				}


			} catch (Exception e) {
				error("Error indexing song", e);
				response.setStatusCode(500);
				response.end();
			}
		});
	}

	private void deleteSong(final HttpServerExchange exchange) {
		HttpServerResponse response = request.response();
		try {
			String id = getParameter(exchange, ("id"));

			// Verify that song exists
			if (songDb.exists(id)) {
				// removes file
				songDb.delete(id);

				String title = indexDb.getTitle(id);

				// removes document from index
				indexDb.removeDocument(id);


				response.setStatusCode(200);

				String mimeType = MimeParser.bestMatch(request.headers().get(HttpHeaders.ACCEPT), MIME_TEXT_SONG, MIME_TEXT_PLAIN, MIME_TEXT_HTML);
				switch (mimeType) {
					case MIME_TEXT_HTML:
						StringBuilder out = new StringBuilder();
						String role = isAdministrator(sessionKey) ? "admin" : "user";
						Templates.header(out, "My SongBook", role);
						// show home page with message
						Templates.alertSongRemovedSuccessfully(out, title == null ? id : title);
						Templates.footer(out);
						response.end(out.toString(), "UTF-8");
						break;
					case MIME_TEXT_PLAIN:
						response.end(id, "UTF-8");
						break;
				}
			} else {
				response.setStatusCode(400);
				response.end("The song doesn't exist and cannot be deleted", "UTF-8");
			}
		} catch (Exception e) {
			error("Error removing song", e);
			response.setStatusCode(500);
			response.end("Error removing song", "UTF-8");
		}
	}

	private void consoleApi(final HttpServerExchange exchange) {
		HttpServerResponse response = request.response();
		response.putHeader(HttpHeaders.CONTENT_TYPE, "text/html");
		StringBuilder out = new StringBuilder();

		String role = isAdministrator(sessionKey) ? "admin" : "user";
		Templates.header(out, "Song Console Api", role);
		Templates.consoleApi(out);
		Templates.footer(out);
		response.end(out.toString(), "UTF-8");
	}

	private void signin(final HttpServerExchange exchange) {
		HttpServerResponse response = request.response();
		response.putHeader(HttpHeaders.CONTENT_TYPE, "text/html");
		StringBuilder out = new StringBuilder();

		String role = isAdministrator(sessionKey) ? "admin" : "user";
		Templates.header(out, "SongBook Admin Page", role);
		Templates.signin(out);
		Templates.footer(out);
		response.end(out.toString(), "UTF-8");
	}

	private void admin(final HttpServerExchange exchange) {
		HttpServerResponse response = request.response();
		response.putHeader(HttpHeaders.CONTENT_TYPE, "text/html");
		StringBuilder out = new StringBuilder();

		String role = isAdministrator(sessionKey) ? "admin" : "user";
		Templates.header(out, "SongBook Admin Page", role);
		Templates.admin(out);
		Templates.footer(out);
		response.end(out.toString(), "UTF-8");
	}

	private void adminCommand(final HttpServerExchange exchange) {
		HttpServerResponse response = request.response();
		response.putHeader(HttpHeaders.CONTENT_TYPE, "text/html");

		StringBuilder out = new StringBuilder();

		String role = isAdministrator(sessionKey) ? "admin" : "user";
		Templates.header(out, "Administration - My SongBook", role);

		String section = QueryStringDecoder.decodeComponent(getParameter(exchange, ("section")));
		String command = QueryStringDecoder.decodeComponent(getParameter(exchange, ("command")));
		switch (section) {
			case "index":
				switch (command) {
					case "reset":
						try {
							long start = System.currentTimeMillis();
							songDb.clearCache();
							indexDb.analyzeSongs();

							long end = System.currentTimeMillis();
							logger.info("Opened index in " + (end - start) + " milliseconds.");
							Templates.alertSongReindexed(out);
							Templates.admin(out);
							response.setStatusCode(200);
						} catch (IOException e) {
							error("Can't initialize index in " + getDataRoot().resolve("index"), e);
							Templates.alertIndexingError(out);
							Templates.admin(out);
							response.setStatusCode(500);
						}
						break;
					default:
						Templates.alertCommandNotSupported(out);
						Templates.admin(out);
						response.setStatusCode(500);
						break;
				}
				break;
			default:
				Templates.alertCommandNotSupported(out);
				Templates.admin(out);
				response.setStatusCode(500);
				break;

		}
		Templates.footer(out);
		response.end(out.toString(), "UTF-8");
	}

	private Path getWebRoot() {
		final String webRoot = System.getenv("WEB_ROOT");
		return Paths.get(webRoot == null ? DEFAULT_WEB_ROOT : webRoot);
	}

	private static Path getDataRoot() {
		final String dataRoot = System.getenv("DATA_ROOT");
		return Paths.get(dataRoot == null ? DEFAULT_DATA_ROOT : dataRoot);
	}

	private Path getSongsPath() {
		final String songRoot = System.getenv("SONGS_ROOT");
		return songRoot == null ? getDataRoot().resolve("songs") : Paths.get(songRoot);
	}

	private int getPort() {
		final String portString = System.getenv("PORT");
		int port = DEFAULT_PORT;
		if (portString != null) {
			try {
				port = Integer.parseInt(portString);
			} catch (NumberFormatException e) {
				// doesn't matter;
			}
		}
		return port;
	}

	private String getHost() {
		String host = System.getenv("HOST");
		if (host == null) host = System.getenv("HOSTNAME");
		return host == null ? DEFAULT_HOST : host;
	}


	// Security



	private void createAdminKey() {
		// creates administrator key when it's null
		long timestamp = System.currentTimeMillis();
		String timestampString = Long.toHexString(timestamp);
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			digest.update(timestampString.getBytes(), 0, timestampString.length());
			administratorKey = new BigInteger(1, digest.digest()).toString(16);
		} catch (NoSuchAlgorithmException e) {
			administratorKey = timestampString;
		}
		logger.info("Created administrator key: '" + administratorKey + "'.");
		writeKeys();
	}

	/**
	 * Searches for keys on server to initialize administratorKey and userKey.
	 */
	private void readKeys() {
		try {
			final Path administratorKeyPath = getDataRoot().resolve(ADMINISTRATOR_KEY_PATH);
			if (Files.exists(administratorKeyPath)) {
				final List<String> allLines = Files.readAllLines(administratorKeyPath);
				if (allLines.isEmpty() == false) {
					administratorKey = allLines.get(allLines.size() - 1);

					showKeyCreationAlert = Files.exists(getDataRoot().resolve(ADMINISTRATOR_ACTIVATED_PATH)) == false;
				}
			}
		} catch (IOException e) {
			error("Could not read administrator key", e);
		}

		try {
			final Path userKeyPath = getDataRoot().resolve("user.key");
			if (Files.exists(userKeyPath)) {
				final List<String> allLines = Files.readAllLines(userKeyPath);
				if (allLines.isEmpty() == false) {
					userKey = allLines.get(allLines.size() - 1);
				}
			}
		} catch (IOException e) {
			error("Could not read user key", e);
		}
	}

	/**
	 * Writes administratorKey and userKey to file system.
	 */
	private void writeKeys() {
		if (administratorKey != null) {
			try {
				final Path administratorKeyPath = getDataRoot().resolve(ADMINISTRATOR_KEY_PATH);
				Files.write(administratorKeyPath, Collections.singleton(administratorKey));
				showKeyCreationAlert = true;

				final Path administratorActivatedPath = getDataRoot().resolve(ADMINISTRATOR_ACTIVATED_PATH);
				if (Files.exists(administratorActivatedPath)) {
					Files.delete(administratorActivatedPath);
				}
			} catch (IOException e) {
				error("Could not write administrator key", e);
			}
		}

		if (userKey != null) {
			try {
				final Path userKeyPath = getDataRoot().resolve("user.key");
				Files.write(userKeyPath, Collections.singleton(userKey));
				showKeyCreationAlert = true;
			} catch (IOException e) {
				error("Could not write user key", e);
			}
		}
	}

	/**
	 * Checks if key allows to be administrator
	 */
	private boolean isAdministrator(String requestKey) {
		return administratorKey == null || administratorKey.equals(requestKey);
	}

	/**
	 * Checks if key allows to be user
	 */
	private boolean isUser(String requestKey) {
		return userKey == null || userKey.equals(requestKey);
	}

	private String getRole(HttpServerExchange exchange) {
		return isAdministrator(exchange.getAttachment(ADMIN_KEY)) ? "admin" : "user";
	}

	protected Undertow createServer(PathTemplateHandler pathHandler) {

		// Adds admin attribute and checks user attribute
		HttpHandler administrationHandler = exchange -> {
			String sessionKey = sessionKey(exchange);

			if ( isUser(sessionKey) ) {
				exchange.putAttachment(ADMIN_KEY, sessionKey);
				pathHandler.handleRequest(exchange);
			} else {
				throw new ServerException(StatusCodes.UNAUTHORIZED);
			}
		};

		// Adds Cross Origin is needed
		HttpHandler allowCrossOriginHandler = exchange -> {
            String origin = getHeader(exchange, Headers.ORIGIN);
            if (origin != null) {
                exchange.getResponseHeaders().put(Headers.ORIGIN, origin);
            }
            administrationHandler.handleRequest(exchange);
        };

		// Handles exceptions (including ServerException)
		ExceptionHandler exceptionHandler = exceptionHandler(allowCrossOriginHandler);
		exceptionHandler.addExceptionHandler(ServerException.class, (exchange) -> {
			Throwable exception = exchange.getAttachment(ExceptionHandler.THROWABLE);
			if (exception instanceof ServerException) {
				((ServerException) exception).serveError(getRole(exchange), exchange);
			} else {
				// TODO create real error message
				String message = exception.getMessage();
				exchange.setResponseCode(StatusCodes.INTERNAL_SERVER_ERROR);
				exchange.getResponseSender().send(createMessage(message, isAskingForJson(exchange)));
			}
		});

		// Logs all requests
		HttpHandler logHandler = (HttpServerExchange exchange) -> {
			long start = System.currentTimeMillis();
			exceptionHandler.handleRequest(exchange);
			long end = System.currentTimeMillis();
			long time = end - start < 0 ? 0 : end - start;
			info("[" + exchange.getRequestMethod() + "]" + exchange.getRequestPath() + " in " + time + " ms");
		};

		Undertow.Builder builder = Undertow.builder();
		builder.addHttpListener(port, "localhost");
		builder.setHandler(gracefulShutdown(urlDecodingHandler("UTF-8", logHandler)));

		info("Listens on port " + port);

		return builder.build();
	}

	protected boolean isAskingForJson(HttpServerExchange exchange) {
		HeaderValues values = exchange.getRequestHeaders().get(Headers.ACCEPT);
		return values != null && values.getFirst().contains("application/json");
	}

	protected String createMessage(String message, boolean json) {
		return json ? "{ \"message\": \"" + message + "\"}" : message;
	}

	protected void send(HttpServerExchange exchange, Supplier<ReadableByteChannel> readSupplier) {
		Sender sender = exchange.getResponseSender();
		try (ReadableByteChannel modelChannel = readSupplier.get()) {
			ByteBuffer buffer = ByteBuffer.allocateDirect(1024*8);

			int bytesCount = modelChannel.read(buffer);
			while (bytesCount > 0) {
				buffer.flip();
				sender.send(buffer);
				buffer.clear();

				bytesCount = modelChannel.read(buffer);
			}
		} catch (IOException e) {
			error("Can't read '" + exchange.getRequestPath() + "'", e);
		} finally {
			sender.close();
		}
	}

	protected void write(HttpServerExchange exchange, Supplier<WritableByteChannel> writeSupplier) {
		ReadableByteChannel requestChannel = exchange.getRequestChannel();
		try (WritableByteChannel modelChannel = writeSupplier.get()) {

			ByteBuffer buffer = ByteBuffer.allocateDirect(1024*8);

			int bytesCount = requestChannel.read(buffer);
			while (bytesCount > 0) {
				buffer.flip();
				modelChannel.write(buffer);
				buffer.clear();

				bytesCount = requestChannel.read(buffer);
			}
		} catch (IOException e) {
			error("Can't write '/" + exchange.getRequestPath() + "'", e);
		} finally {
			try {
				requestChannel.close();
			} catch (IOException e) {
				error("Can't write '/" + exchange.getRequestPath() + "'", e);
			}
		}
	}

	protected String getHeader(HttpServerExchange exchange, HttpString header) {
		Deque<String> deque = exchange.getRequestHeaders().get(header);
		return deque == null ? null : deque.element();
	}

	protected String getParameter(HttpServerExchange exchange, String parameter) {
		Deque<String> deque = exchange.getQueryParameters().get(parameter);
		return deque == null ? null : deque.element();
	}

	private HttpHandler get(HttpHandler handler) {
		return methodFilterHandler(handler, Methods.GET);
	}

	private HttpHandler post(HttpHandler handler) {
		return methodFilterHandler(handler, Methods.POST);
	}

	private HttpHandler put(HttpHandler handler) {
		return methodFilterHandler(handler, Methods.PUT);
	}

	private HttpHandler delete(HttpHandler handler) {
		return methodFilterHandler(handler, Methods.DELETE);
	}

	private HttpHandler adminAccess(HttpHandler handler) {
		return exchange -> {
			String sessionKey = exchange.getAttachment(ADMIN_KEY);
			if (isAdministrator(sessionKey)) {
				handler.handleRequest(exchange);
			} else {
				throw new ServerException(StatusCodes.UNAUTHORIZED);
			}
		};
	}

	private HttpHandler methodFilterHandler(HttpHandler handler, HttpString method) {
		return exchange -> {
			if (method.equals(exchange.getRequestMethod())) {
				handler.handleRequest(exchange);
			} else {
				throw new ServerException(StatusCodes.METHOD_NOT_ALLOWED);
			}
		};
	}

	private void info(String message) {
		logger.log(Level.INFO, message);
	}

	private void error(String message, IOException e) {
		logger.log(Level.SEVERE, message, e);
	}
}
