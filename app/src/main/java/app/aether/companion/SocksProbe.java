package app.aether.companion;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

final class SocksProbe {
    private SocksProbe() {}

    static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            socket.setSoTimeout(500);
            OutputStream output = socket.getOutputStream();
            output.write(new byte[]{0x05, 0x01, 0x00});
            output.flush();
            InputStream input = socket.getInputStream();
            return input.read() == 0x05 && input.read() == 0x00;
        } catch (Exception ignored) {
            return false;
        }
    }
}
