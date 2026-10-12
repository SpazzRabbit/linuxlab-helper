package dev.linuxlab.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TCP link to lltouch, the module inside the X server of Linux Lab.
 * Frames: type, arg, x, y (little endian int16).
 */
final class Link {
    interface Listener {
        void onLinkState(boolean connected);
    }

    /** a finger: arg is its number */
    static final int TOUCH_BEGIN = 1;
    static final int TOUCH_UPDATE = 2;
    static final int TOUCH_END = 3;
    /** the mouse pointer: arg is the mask of buttons that are down */
    static final int POINTER = 4;

    static final int PORT = 54398;
    private static final String GREETING = "LLTOUCH";
    private static final byte[] POISON = new byte[0];

    private final Listener listener;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean wanted;
    private volatile boolean connected;
    private volatile boolean stopped;
    private volatile int rootWidth;
    private volatile int rootHeight;

    Link(Listener listener) {
        this.listener = listener;
        Thread thread = new Thread(this::run, "lltouch-link");
        thread.setDaemon(true);
        thread.start();
    }

    /** Keep trying to connect only while the Linux Lab window is on screen. */
    void setWanted(boolean value) {
        if (wanted != value) {
            wanted = value;
            queue.offer(POISON);
        }
    }

    boolean isConnected() {
        return connected;
    }

    int rootWidth() {
        return rootWidth;
    }

    int rootHeight() {
        return rootHeight;
    }

    void send(int type, int arg, int x, int y) {
        if (connected) {
            queue.offer(new byte[] {(byte) type, (byte) arg, (byte) x, (byte) (x >> 8), (byte) y, (byte) (y >> 8)});
        }
    }

    void stop() {
        stopped = true;
        queue.offer(POISON);
    }

    private void run() {
        while (!stopped) {
            if (!wanted) {
                take(1000);
                continue;
            }
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
                readHello(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                queue.clear();
                setConnected(true);
                while (!stopped && wanted) {
                    byte[] frame = take(2000);
                    if (frame == null) {
                        // an idle link is probed with a harmless frame so a dead host is noticed
                        out.write(new byte[] {0, 0, 0, 0, 0, 0});
                    } else if (frame != POISON) {
                        out.write(frame);
                    }
                    out.flush();
                }
            } catch (IOException e) {
                // host not running or gone: retry below
            }
            setConnected(false);
            if (!stopped && wanted) {
                take(1000);
            }
        }
    }

    private void readHello(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0 || line.length() > 64) {
                throw new IOException("bad hello");
            }
            line.append((char) c);
        }
        String[] parts = line.toString().trim().split(" ");
        if (parts.length != 3 || !GREETING.equals(parts[0])) {
            throw new IOException("bad hello");
        }
        try {
            rootWidth = Integer.parseInt(parts[1]);
            rootHeight = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IOException("bad hello");
        }
    }

    private byte[] take(long millis) {
        try {
            return queue.poll(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    private void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
            listener.onLinkState(value);
        }
    }
}
