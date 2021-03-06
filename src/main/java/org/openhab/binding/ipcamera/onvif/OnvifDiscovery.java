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

package org.openhab.binding.ipcamera.onvif;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ipcamera.internal.IpCameraDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.CharsetUtil;

/**
 * The {@link OnvifDiscovery} is responsible for finding cameras that are Onvif using UDP multicast.
 *
 * @author Matthew Skinner - Initial contribution
 */

@NonNullByDefault
public class OnvifDiscovery {
    IpCameraDiscoveryService ipCameraDiscoveryService;
    private final Logger logger = LoggerFactory.getLogger(OnvifDiscovery.class);
    public ArrayList<DatagramPacket> listOfReplys = new ArrayList<DatagramPacket>(10);

    public OnvifDiscovery(IpCameraDiscoveryService ipCameraDiscoveryService) {
        this.ipCameraDiscoveryService = ipCameraDiscoveryService;
    }

    public @Nullable NetworkInterface getLocalNIF() {
        try {
            for (Enumeration<NetworkInterface> enumNetworks = NetworkInterface.getNetworkInterfaces(); enumNetworks
                    .hasMoreElements();) {
                NetworkInterface networkInterface = enumNetworks.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr
                        .hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().toString().length() < 18
                            && inetAddress.isSiteLocalAddress()) {
                        return networkInterface;
                    }
                }
            }
        } catch (SocketException ex) {
        }
        return null;
    }

    String fetchXML(String message, String sectionHeading, String key) {
        String result = "";
        int sectionHeaderBeginning = 0;
        if (!sectionHeading.equals("")) {// looking for a sectionHeading
            sectionHeaderBeginning = message.indexOf(sectionHeading);
        }
        if (sectionHeaderBeginning == -1) {
            // logger.debug("{} was not found in :{}", sectionHeading, message);
            return "";
        }
        int startIndex = message.indexOf(key, sectionHeaderBeginning + sectionHeading.length());
        if (startIndex == -1) {
            // logger.debug("{} was not found in :{}", key, message);
            return "";
        }
        int endIndex = message.indexOf("<", startIndex + key.length());
        if (endIndex > startIndex) {
            result = message.substring(startIndex + key.length(), endIndex);
        }
        // remove any quotes and anything after the quote.
        sectionHeaderBeginning = result.indexOf("\"");
        if (sectionHeaderBeginning > 0) {
            result = result.substring(0, sectionHeaderBeginning);
        }
        return result;
    }

    void searchReply(String url, String xml) {
        String ipAddress = "";
        String temp = url;
        int onvifPort = 80;

        logger.info("Camera found at xAddr:{}", url);
        int endIndex = temp.indexOf(" ");// Some xAddr have two urls with a space in between.
        if (endIndex > 0) {
            temp = temp.substring(0, endIndex);// Use only the first url from now on.
        }

        int beginIndex = temp.indexOf(":") + 3;// add 3 to ignore the :// after http.
        int secondIndex = temp.indexOf(":", beginIndex); // find second :
        endIndex = temp.indexOf("/", beginIndex);
        if (secondIndex > beginIndex && endIndex > secondIndex) {// http://192.168.0.1:8080/onvif/device_service
            ipAddress = temp.substring(beginIndex, secondIndex);
            onvifPort = Integer.parseInt(temp.substring(secondIndex + 1, endIndex));
        } else {// // http://192.168.0.1/onvif/device_service
            ipAddress = temp.substring(beginIndex, endIndex);
        }
        logger.debug("Camera IP:{} and ONVIF PORT:{}", ipAddress, onvifPort);
        String brand = checkForBrand(xml);
        if (brand.equals("ONVIF")) {
            try {
                brand = getBrandFromLoginPage(ipAddress);
            } catch (IOException e) {
                brand = "ONVIF";
            }
        }
        ipCameraDiscoveryService.newCameraFound(brand, ipAddress, onvifPort);
    }

    void processCameraReplys() {
        for (DatagramPacket packet : listOfReplys) {
            logger.trace(packet.toString());
            String xml = packet.content().toString(CharsetUtil.UTF_8);
            String xAddr = fetchXML(xml, "", "<d:XAddrs>");
            if (!xAddr.equals("")) {
                // logger.trace("Discovery packet back from camera:{}", xml);
                searchReply(xAddr, xml);
            } else if (xml.contains("onvif")) {
                logger.info("Possible ONVIF camera found at:{}", packet.sender().getHostString());
                ipCameraDiscoveryService.newCameraFound("ONVIF", packet.sender().getHostString(), 80);
            }
        }
    }

    String checkForBrand(String response) {
        if (response.toLowerCase().contains("amcrest")) {
            return "DAHUA";
        } else if (response.toLowerCase().contains("dahua")) {
            return "DAHUA";
        } else if (response.toLowerCase().contains("foscam")) {
            return "FOSCAM";
        } else if (response.toLowerCase().contains("hikvision")) {
            return "HIKVISION";
        } else if (response.toLowerCase().contains("instar")) {
            return "INSTAR";
        } else if (response.toLowerCase().contains("doorbird")) {
            return "DOORBIRD";
        } else if (response.toLowerCase().contains("/doc/page/login.asp")) {
            return "HIKVISION";
        }
        return "ONVIF";// generic camera
    }

    public String getBrandFromLoginPage(String hostname) throws IOException {
        URL url = new URL("http://" + hostname);
        String brand = "ONVIF";
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(2000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        try {
            connection.connect();
            // int status = connection.getResponseCode();
            BufferedReader reply = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String response = "";
            String temp;
            while ((temp = reply.readLine()) != null) {
                response += temp;
            }
            reply.close();
            logger.trace("Cameras Login page is:{}", response);
            brand = checkForBrand(response);
        } catch (MalformedURLException e) {
        } finally {
            connection.disconnect();
        }
        return brand;
    }

    public void discoverCameras(int port) throws UnknownHostException, InterruptedException {
        String uuid = UUID.randomUUID().toString();
        String xml = "";

        if (port == 3702) {
            xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><e:Header><w:MessageID>uuid:"
                    + uuid
                    + "</w:MessageID><w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action a:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types xmlns:dp0=\"http://www.onvif.org/ver10/network/wsdl\">dp0:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
        }
        ByteBuf discoveryProbeMessage = Unpooled.copiedBuffer(xml, 0, xml.length(), StandardCharsets.UTF_8);
        InetSocketAddress localNetworkAddress = new InetSocketAddress(0);// Listen for replies on all connections.
        InetSocketAddress multiCastAddress = new InetSocketAddress(InetAddress.getByName("239.255.255.250"), port);
        DatagramPacket datagramPacket = new DatagramPacket(discoveryProbeMessage, multiCastAddress,
                localNetworkAddress);
        NetworkInterface networkInterface = getLocalNIF();
        DatagramChannel datagramChannel;

        Bootstrap bootstrap = new Bootstrap().group(new NioEventLoopGroup())
                .channelFactory(new ChannelFactory<NioDatagramChannel>() {
                    @Override
                    public NioDatagramChannel newChannel() {
                        return new NioDatagramChannel(InternetProtocolFamily.IPv4);
                    }
                }).handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(@Nullable ChannelHandlerContext ctx, DatagramPacket msg)
                            throws Exception {
                        msg.retain(1);
                        listOfReplys.add(msg);
                    }
                }).option(ChannelOption.SO_BROADCAST, true).option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.IP_MULTICAST_LOOP_DISABLED, false).option(ChannelOption.SO_RCVBUF, 2048)
                .option(ChannelOption.IP_MULTICAST_TTL, 255).option(ChannelOption.IP_MULTICAST_IF, networkInterface);

        datagramChannel = (DatagramChannel) bootstrap.bind(localNetworkAddress).sync().channel();
        datagramChannel.joinGroup(multiCastAddress, networkInterface).sync();
        ChannelFuture chFuture;
        if (port == 1900) {
            String ssdp = "M-SEARCH * HTTP/1.1\n" + "HOST: 239.255.255.250:1900\n" + "MAN: \"ssdp:discover\"\n"
                    + "MX: 1\n" + "ST: urn:dial-multiscreen-org:service:dial:1\n"
                    + "USER-AGENT: Microsoft Edge/83.0.478.61 Windows\n" + "\n" + "";
            ByteBuf ssdpProbeMessage = Unpooled.copiedBuffer(ssdp, 0, ssdp.length(), StandardCharsets.UTF_8);
            datagramPacket = new DatagramPacket(ssdpProbeMessage, multiCastAddress, localNetworkAddress);
            chFuture = datagramChannel.writeAndFlush(datagramPacket);
        } else {
            chFuture = datagramChannel.writeAndFlush(datagramPacket);
        }
        chFuture.awaitUninterruptibly(2000);
        chFuture = datagramChannel.closeFuture();
        TimeUnit.SECONDS.sleep(5);
        datagramChannel.close();
        chFuture.awaitUninterruptibly(6000);
        processCameraReplys();
        bootstrap.config().group().shutdownGracefully();
    }
}
