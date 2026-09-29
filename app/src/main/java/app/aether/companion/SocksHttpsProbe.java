package app.aether.companion;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class SocksHttpsProbe {
    private static final String HOST = "www.cloudflare.com";

    private SocksHttpsProbe() {}

    static Result verify(int socksPort) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", socksPort), 3000);
            socket.setSoTimeout(8000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            output.write(new byte[]{0x05, 0x01, 0x00});
            output.flush();
            if (readByte(input) != 0x05 || readByte(input) != 0x00) {
                throw new IllegalStateException("SOCKS5 authentication negotiation failed");
            }

            byte[] host = HOST.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write(new byte[]{0x05, 0x01, 0x00, 0x03});
            request.write(host.length);
            request.write(host);
            request.write(0x01);
            request.write(0xbb);
            output.write(request.toByteArray());
            output.flush();

            if (readByte(input) != 0x05 || readByte(input) != 0x00) {
                throw new IllegalStateException("SOCKS5 could not reach the internet");
            }
            readByte(input); // reserved
            int addressType = readByte(input);
            if (addressType == 0x01) skipFully(input, 4);
            else if (addressType == 0x04) skipFully(input, 16);
            else if (addressType == 0x03) skipFully(input, readByte(input));
            else throw new IllegalStateException("Unexpected SOCKS5 address type");
            skipFully(input, 2);

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(socket, HOST, 443, true)) {
                tls.setSoTimeout(10000);
                tls.startHandshake();
                OutputStream tlsOut = tls.getOutputStream();
                tlsOut.write(("GET /cdn-cgi/trace HTTP/1.1\r\n" +
                        "Host: " + HOST + "\r\n" +
                        "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                tlsOut.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        tls.getInputStream(), StandardCharsets.UTF_8));
                String line;
                boolean body = false;
                String ip = "";
                String warp = "";
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) { body = true; continue; }
                    if (!body) continue;
                    if (line.startsWith("ip=")) ip = line.substring(3).trim();
                    if (line.startsWith("warp=")) warp = line.substring(5).trim();
                }
                if (ip.isEmpty()) throw new IllegalStateException("Internet test returned no exit IP");
                return new Result(ip, warp);
            }
        }
    }

    private static int readByte(InputStream input) throws Exception {
        int value = input.read();
        if (value < 0) throw new EOFException("Unexpected end of SOCKS5 response");
        return value;
    }

    private static void skipFully(InputStream input, int count) throws Exception {
        while (count-- > 0) readByte(input);
    }

    static final class Result {
        final String ip;
        final String warp;

        Result(String ip, String warp) {
            this.ip = ip;
            this.warp = warp;
        }
    }
}
