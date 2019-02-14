package io.gazetteer.tileserver;

import io.gazetteer.tilesource.TileSource;
import io.gazetteer.tilesource.XYZ;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@ChannelHandler.Sharable
public class TileServerHandler extends SimpleChannelInboundHandler<HttpRequest> {

  private static final Logger LOGGER = LoggerFactory.getLogger(TileServerHandler.class);

  private static final AsciiString TEXT_TYPE = AsciiString.cached("text/plain");

  private static final AsciiString HTML_TYPE = AsciiString.cached("text/html");

  private static final AsciiString ENCODING = AsciiString.cached("gzip");

  private static final long STARTUP_TIME = System.currentTimeMillis();

  private static final String DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";

  private static final String DATE_GMT_TIMEZONE = "GMT";

  private static final AsciiString MAX_AGE =
      AsciiString.cached("public, max-age=0, no-transform"); // disable cache for now

  private static final String ROOT_URI = "/";

  private static final String ROOT_CONTENT = "Gazetteer Tile Server v0.1";

  private static final Pattern TILE_URI =
      Pattern.compile(String.format("/(\\d{1,2})/(\\d{1,6})/(\\d{1,6}).pbf"));

  private final TileSource tileSource;

  public TileServerHandler(TileSource tileSource) {
    this.tileSource = tileSource;
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.flush();
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpRequest req)
      throws ParseException, IOException {

    if (!req.decoderResult().isSuccess()) {
      sendError(ctx);
      return;
    }

    String uri = req.uri().equals("/") ? "/index.html" : req.uri();

    String ifModifiedSince = req.headers().get(IF_MODIFIED_SINCE);
    if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
      SimpleDateFormat dateFormatter = new SimpleDateFormat(DATE_FORMAT, Locale.US);
      long downloadTime = dateFormatter.parse(ifModifiedSince).getTime();
      if (STARTUP_TIME < downloadTime) {
        sendNotModified(ctx);
        return;
      }
    }

    Matcher tileMatcher = TILE_URI.matcher(uri);
    if (tileMatcher.matches()) {
      int z = Integer.parseInt(tileMatcher.group(1));
      int x = Integer.parseInt(tileMatcher.group(2));
      int y = Integer.parseInt(tileMatcher.group(3));
      sendTile(ctx, z, x, y);
      return;
    }

    String path = "/www" + uri;
    URL file = getClass().getResource(path);
    if (file != null) {
      sendFile(ctx, file.getPath());
      return;
    }

    sendError(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOGGER.info("Exception caught", cause);
    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
    setDateHeader(response);
    ctx.channel().write(response).addListener(ChannelFutureListener.CLOSE);
  }

  private void sendFile(ChannelHandlerContext ctx, String file) throws IOException {
    Path path = Paths.get(file);
    String contentType = Files.probeContentType(path);
    byte[] bytes = Files.readAllBytes(path);
    ByteBuf data = Unpooled.copiedBuffer(bytes);
    DefaultFullHttpResponse response =
        new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(data));
    setDateHeader(response);
    response.headers().set(CONTENT_TYPE, AsciiString.cached(contentType));
    response.headers().setInt(CONTENT_LENGTH, response.content().readableBytes());
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private void sendTile(ChannelHandlerContext ctx, int z, int x, int y) {
    XYZ xyz = new XYZ(x, y, z);
    tileSource
        .getTile(xyz)
        .thenAccept(
            tile -> {
              if (tile != null) {
                DefaultFullHttpResponse response =
                    new DefaultFullHttpResponse(
                        HTTP_1_1, OK, Unpooled.wrappedBuffer(tile.getBytes()));
                setDateHeader(response);
                response.headers().set(CONTENT_TYPE, tileSource.getMimeType());
                response.headers().setInt(CONTENT_LENGTH, response.content().readableBytes());
                response.headers().set(CONTENT_ENCODING, ENCODING);
                response.headers().set(CACHE_CONTROL, MAX_AGE);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
              } else {
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
              }
            });
  }

  private void sendError(ChannelHandlerContext ctx) {
    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
    setDateHeader(response);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private void sendNotModified(ChannelHandlerContext ctx) {
    FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED);
    setDateHeader(response);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private void setDateHeader(FullHttpResponse response) {
    SimpleDateFormat dateFormatter = new SimpleDateFormat(DATE_FORMAT, Locale.US);
    dateFormatter.setTimeZone(TimeZone.getTimeZone(DATE_GMT_TIMEZONE));
    Calendar time = new GregorianCalendar();
    response.headers().set(DATE, dateFormatter.format(time.getTime()));
  }
}
