package dev.linuxlab.helper;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root through su, for the chroot mode of Linux Lab: whether the tablet has it, and llroot, the root
 * service that runs the desktop in a chroot of Linux Lab's tree (overlay/root/llroot.c).
 *
 * llroot comes with this app and is copied into its own files, where Linux Lab cannot change it: it
 * runs as root. It only ever starts the desktop of Linux Lab, as Linux Lab's own user.
 *
 * Everything here waits for su, a second or longer (Magisk asks the person first): never on the main
 * thread.
 */
final class Root {
    private static final String TAG = "LinuxLabHelper";
    static final String ROOTFS = "/data/user/0/" + Linux.PACKAGE + "/linuxlab";
    static final int PORT = 54396;

    /** Not known yet, no su at all, su that refuses this app, root. */
    static final int UNKNOWN = -1;
    static final int NONE = 0;
    static final int DENIED = 1;
    static final int OK = 2;

    /** apps that hand out root: with one of them and no su, it is root that this app was not given */
    private static final String[] MANAGERS = {
        "me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra", "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk", "io.github.huskydg.magisk", "me.bmax.apatch",
    };

    private Root() {
    }

    /** Whether root can be had, asking su for real. */
    static int check(Context context) {
        String[] answer;
        try {
            answer = su(context, "check");
        } catch (IOException e) {
            Log.i(TAG, "no su: " + e.getMessage());
            return managerInstalled(context) ? DENIED : NONE;
        }
        Log.i(TAG, "su llroot check: exit " + answer[0] + ", " + answer[1]);
        return answer[1].startsWith("ok ") ? OK : DENIED;
    }

    /** Starts llroot, unless it runs already. True when it answers afterwards. */
    static boolean start(Context context) {
        if (answers()) {
            return true;
        }
        try {
            String[] answer = su(context, "serve");
            Log.i(TAG, "su llroot serve: exit " + answer[0] + ", " + answer[1]);
        } catch (IOException e) {
            Log.w(TAG, "su llroot serve: " + e.getMessage());
            return false;
        }
        for (int i = 0; i < 20 && !answers(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return answers();
    }

    /** llroot answers on its port. */
    static boolean answers() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write("hello\n".getBytes(StandardCharsets.US_ASCII));
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return line != null && line.startsWith("LLROOT ");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean managerInstalled(Context context) {
        PackageManager packages = context.getPackageManager();
        for (String name : MANAGERS) {
            try {
                packages.getPackageInfo(name, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                // not this one
            }
        }
        return false;
    }

    /** Runs "llroot <command> <tree>" through su: {exit code, first line of the output}. */
    private static String[] su(Context context, String command) throws IOException {
        File binary = binary(context);
        Process process = new ProcessBuilder("su", "-c", binary.getPath() + " " + command + " " + ROOTFS)
            .redirectErrorStream(true)
            .start();
        String line;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            line = out.readLine();
            if (!process.waitFor(40, TimeUnit.SECONDS)) {
                process.destroy();
                throw new IOException("su did not answer");
            }
        } catch (InterruptedException e) {
            process.destroy();
            throw new IOException("interrupted");
        }
        return new String[] {String.valueOf(process.exitValue()), line == null ? "" : line};
    }

    /** llroot from the assets, copied anew whenever this app was updated. */
    private static File binary(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "llroot");
        long installed;
        try {
            installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            installed = System.currentTimeMillis();
        }
        if (file.isFile() && file.canExecute() && file.lastModified() >= installed) {
            return file;
        }
        File fresh = new File(context.getFilesDir(), "llroot.new");
        try (InputStream in = context.getAssets().open("llroot"); OutputStream out = new FileOutputStream(fresh)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
        }
        if (!fresh.setExecutable(true, true) || !fresh.renameTo(file)) {
            throw new IOException("cannot put llroot into place");
        }
        return file;
    }
}
