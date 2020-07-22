/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.binding.ipcamera.internal;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CONFIG_FFMPEG_OUTPUT;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

/**
 * The {@link StreamServerHandler} class is responsible for handling streams and sending any requested files to Openhabs
 * features.
 *
 * @author Matthew Skinner - Initial contribution
 */

@NonNullByDefault
public class StreamServerHandler extends ChannelInboundHandlerAdapter {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private IpCameraHandler ipCameraHandler;
    private boolean handlingMjpeg = false; // used to remove ctx from group when handler is removed.
    private boolean handlingSnapshotStream = false; // used to remove ctx from group when handler is removed.
    private byte[] incomingJpeg = new byte[0];
    String whiteList = "";
    int recievedBytes = 0;
    int count = 0;
    boolean updateSnapshot = false;
    boolean onvifEvent = false;

    public StreamServerHandler(IpCameraHandler ipCameraHandler) {
        this.ipCameraHandler = ipCameraHandler;
        whiteList = ipCameraHandler.getWhiteList();
    }

    @Override
    public void handlerAdded(@Nullable ChannelHandlerContext ctx) {
        // logger.trace("Opening a StreamServerHandler.");
    }

    @Override
    public void channelRead(@Nullable ChannelHandlerContext ctx, @Nullable Object msg) throws Exception {
        if (ctx == null) {
            return;
        }

        @Nullable
        HttpContent content = null;
        try {
            if (msg instanceof HttpRequest) {
                HttpRequest httpRequest = (HttpRequest) msg;
                // logger.debug("Stream Server recieved request \t{}:{}", httpRequest.method(), httpRequest.uri());
                if (!whiteList.equals("DISABLE")) {
                    String requestIP = "("
                            + ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress() + ")";
                    if (!whiteList.contains(requestIP)) {
                        logger.warn("The request made from {} was not in the whitelist and will be ignored.",
                                requestIP);
                        return;
                    }
                }
                if ("GET".equalsIgnoreCase(httpRequest.method().toString())) {
                    logger.debug("Stream Server recieved request \tGET:{}", httpRequest.uri());
                    // Some browsers send a query string after the path when refreshing a picture.
                    QueryStringDecoder queryStringDecoder = new QueryStringDecoder(httpRequest.uri());
                    switch (queryStringDecoder.path()) {
                        case "/ipcamera.m3u8":
                            if (ipCameraHandler.ffmpegHLS != null) {
                                if (!ipCameraHandler.ffmpegHLS.getIsAlive()) {
                                    if (ipCameraHandler.ffmpegHLS != null) {
                                        ipCameraHandler.ffmpegHLS.startConverting();
                                    }
                                }
                            } else {
                                ipCameraHandler.setupFfmpegFormat("HLS");
                            }
                            if (ipCameraHandler.ffmpegHLS != null) {
                                ipCameraHandler.ffmpegHLS.setKeepAlive(8);
                            }
                            sendFile(ctx, httpRequest.uri(), "application/x-mpegurl");
                            ctx.close();
                            return;
                        case "/ipcamera.mpd":
                            sendFile(ctx, httpRequest.uri(), "application/dash+xml");
                            return;
                        case "/ipcamera.gif":
                            sendFile(ctx, httpRequest.uri(), "image/gif");
                            return;
                        case "/ipcamera.jpg":
                            if (!ipCameraHandler.snapshotPolling && ipCameraHandler.snapshotUri != "") {
                                ipCameraHandler.sendHttpGET(ipCameraHandler.snapshotUri);
                            }
                            if (ipCameraHandler.currentSnapshot.length == 1) {
                                logger.warn("ipcamera.jpg was requested but there is no jpg in ram to send.");
                                return;
                            }
                            sendSnapshotImage(ctx, "image/jpg");
                            return;
                        case "/snapshots.mjpeg":
                            handlingSnapshotStream = true;
                            ipCameraHandler.startSnapshotPolling();
                            ipCameraHandler.setupSnapshotStreaming(true, ctx, false);
                            return;
                        case "/ipcamera.mjpeg":
                            ipCameraHandler.setupMjpegStreaming(true, ctx);
                            handlingMjpeg = true;
                            return;
                        case "/autofps.mjpeg":
                            handlingSnapshotStream = true;
                            ipCameraHandler.setupSnapshotStreaming(true, ctx, true);
                            return;
                        case "/instar":
                            InstarHandler instar = new InstarHandler(ipCameraHandler);
                            instar.alarmTriggered(httpRequest.uri().toString());
                            ctx.close();
                            return;
                        case "/ipcamera0.ts":
                            // TimeUnit.SECONDS.sleep(3);// was 6
                        default:
                            if (httpRequest.uri().contains(".ts")) {
                                sendFile(ctx, queryStringDecoder.path(), "video/MP2T");
                            } else if (httpRequest.uri().contains(".jpg")) {
                                // Allow access to the preroll and postroll jpg files
                                sendFile(ctx, queryStringDecoder.path(), "image/jpg");
                            } else if (httpRequest.uri().contains(".m4s")) {
                                sendFile(ctx, queryStringDecoder.path(), "video/mp4");
                            } else if (httpRequest.uri().contains(".mp4")) {
                                sendFile(ctx, queryStringDecoder.path(), "video/mp4");
                            }
                            ctx.close();
                            return;
                    }
                } else if ("POST".equalsIgnoreCase(httpRequest.method().toString())) {
                    switch (httpRequest.uri()) {
                        case "/ipcamera.jpg":
                            break;
                        case "/snapshot.jpg":
                            updateSnapshot = true;
                            break;
                        case "/OnvifEvent":
                            onvifEvent = true;
                            break;
                        default:
                            logger.debug("Stream Server recieved unknown request \tPOST:{}", httpRequest.uri());
                            break;
                    }
                }
            }
            if (msg instanceof HttpContent) {
                content = (HttpContent) msg;
                int index = 0;

                if (recievedBytes == 0) {
                    incomingJpeg = new byte[content.content().capacity()];
                } else {
                    byte[] temp = incomingJpeg;
                    incomingJpeg = new byte[recievedBytes + content.content().capacity()];

                    for (; index < temp.length; index++) {
                        incomingJpeg[index] = temp[index];
                    }
                }
                for (int i = 0; i < content.content().capacity(); i++) {
                    incomingJpeg[index++] = content.content().getByte(i);
                }
                recievedBytes = incomingJpeg.length;
                if (content instanceof LastHttpContent) {
                    if (updateSnapshot) {
                        ipCameraHandler.lockCurrentSnapshot.lock();
                        ipCameraHandler.currentSnapshot = incomingJpeg;
                        ipCameraHandler.lockCurrentSnapshot.unlock();
                        ipCameraHandler.processSnapshot();
                    } else if (onvifEvent) {
                        logger.debug("StreamServer recieved a new event");
                        ipCameraHandler.onvifCamera.eventRecieved(new String(incomingJpeg, StandardCharsets.UTF_8));
                    } else {
                        if (recievedBytes > 1000) {
                            ipCameraHandler.sendMjpegFrame(incomingJpeg, ipCameraHandler.mjpegChannelGroup);
                        }
                    }
                    recievedBytes = 0;
                }
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void sendSnapshotImage(ChannelHandlerContext ctx, String contentType) throws IOException {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        ipCameraHandler.lockCurrentSnapshot.lock();
        ByteBuf snapshotData = Unpooled.copiedBuffer(ipCameraHandler.currentSnapshot);
        ipCameraHandler.lockCurrentSnapshot.unlock();
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, snapshotData.readableBytes());
        response.headers().add("Access-Control-Allow-Origin", "*");
        response.headers().add("Access-Control-Expose-Headers", "*");
        ctx.channel().write(response);
        ctx.channel().write(snapshotData);
        ByteBuf footerBbuf = Unpooled.copiedBuffer("\r\n", 0, 2, StandardCharsets.UTF_8);
        ctx.channel().writeAndFlush(footerBbuf);
    }

    private void sendFile(ChannelHandlerContext ctx, String fileUri, String contentType) throws IOException {
        File file = new File(ipCameraHandler.config.get(CONFIG_FFMPEG_OUTPUT).toString() + fileUri);
        ChunkedFile chunkedFile = new ChunkedFile(file);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, chunkedFile.length());
        response.headers().add("Access-Control-Allow-Origin", "*");
        response.headers().add("Access-Control-Expose-Headers", "*");
        ctx.channel().write(response);
        ctx.channel().write(chunkedFile);
        ByteBuf footerBbuf = Unpooled.copiedBuffer("\r\n", 0, 2, StandardCharsets.UTF_8);
        ctx.channel().writeAndFlush(footerBbuf);
    }

    @Override
    public void channelReadComplete(@Nullable ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    public void exceptionCaught(@Nullable ChannelHandlerContext ctx, @Nullable Throwable cause) throws Exception {
        if (ctx == null || cause == null) {
            return;
        }
        if (cause.toString().contains("Connection reset by peer")) {
            logger.trace("Connection reset by peer.");
        } else if (cause.toString().contains("An established connection was aborted by the software")) {
            logger.debug("An established connection was aborted by the software");
        } else if (cause.toString().contains("An existing connection was forcibly closed by the remote host")) {
            logger.debug("An existing connection was forcibly closed by the remote host");
        } else if (cause.toString().contains("(No such file or directory)")) {
            logger.info(
                    "IpCameras file server could not find the requested file. This may happen if ffmpeg is still creating the file.");
        } else {
            logger.warn("Exception caught from stream server:{}", cause);
        }
        ctx.close();
    }

    @Override
    public void userEventTriggered(@Nullable ChannelHandlerContext ctx, @Nullable Object evt) throws Exception {
        if (ctx == null) {
            return;
        }
        // logger.trace("Stream server:{}.", evt);
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
                logger.debug("Stream server is going to close an idle channel.");
                ctx.close();
            }
        }
    }

    @Override
    public void handlerRemoved(@Nullable ChannelHandlerContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.close();
        // logger.trace("Closing a StreamServerHandler.");
        if (handlingMjpeg) {
            ipCameraHandler.setupMjpegStreaming(false, ctx);
        } else if (handlingSnapshotStream) {
            handlingSnapshotStream = false;
            ipCameraHandler.setupSnapshotStreaming(false, ctx, false);
        }
    }
}
