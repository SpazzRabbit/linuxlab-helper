/*
 * llroot: the root side of the chroot mode of Linux Lab.
 *
 * Linux Lab runs Ubuntu under PRoot inside its Android app: every system call of every program is
 * caught and rewritten, the app's seccomp filter and SELinux domain refuse a good part of Linux, and
 * root is make-believe. On a rooted tablet the same tree of files can be entered with chroot instead:
 * the programs then talk to the kernel directly. Linux Lab itself is kept as it is: it starts its X
 * server under PRoot and shows its picture; only the desktop and what is started from it run here.
 *
 *   llroot serve <rootfs>   the service, started by the helper app through su; it puts itself in the
 *                           background, and a second one sees the first and leaves
 *   llroot check <rootfs>   says whether the service could run: "ok <uid> <SELinux level>"
 *
 * The service listens on 127.0.0.1:54396 and talks in lines:
 *
 *   hello                   -> "LLROOT 1 <uid of the app> <level>" (to anyone)
 *   env NAME=value          the environment of the desktop, one variable a line
 *   run gnome | run lxqt    -> "started <pid>", later "exit <code>"; the desktop ends when the
 *                              connection is closed or the line "stop" comes
 *
 * Only the user of Linux Lab, the owner of the tree, may run a desktop (the owner of the connection is
 * looked up in /proc/net/tcp), and only one of the two fixed commands. The desktop runs as that user,
 * with its groups (3003 lets it use the network), never as root: everything in the tree is written by
 * Linux Lab and must not run as root. It runs in the SELinux domain of su, but with the level of the
 * app's files, so that what it creates is labelled as the app's own and PRoot can use it afterwards.
 *
 * The user of the desktop is honor, as under PRoot, but with the real uid: /etc/passwd and /etc/group
 * of the tree get a second "honor" with the uid and gid of the app. PRoot never shows that uid, so for
 * it nothing changes; in the chroot getpwuid() finds the name and the home.
 *
 * What the desktop sees: the tree as its root, /proc, /dev with /dev/pts and /sys of the tablet, the
 * shared storage under /tablet and /dev/shm on the /tmp of the tree, as under PRoot; /dev/dri hidden
 * (see hide_dri). And the tree once
 * more under its path on Android, /data/data/<app>/linuxlab and /data/user/0/<app>/linuxlab: PRoot runs
 * with --link2symlink, so a hard link made under it is a symbolic link to that path (perl and the
 * archive of the locales are such links), and in the chroot it would lead nowhere. "sudo" is
 * /opt/linuxlab-gnome/sudo-chroot (fakeroot) when that exists: nothing in the tree is owned by root.
 * All of it lives in a mount namespace of its own; when the desktop ends, every process left in that
 * namespace is ended too, and the mounts go with it.
 *
 * Build (static, for arm64): gcc -O2 -static -Wall -o llroot llroot.c
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <netinet/in.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#define PORT 54396
#define MAX_ENV 512
#define MAX_GROUPS 32

static const char *rootfs;
static uid_t app_uid;
static gid_t app_gid;
static char level[256]; /* "s0:c8,c257,c512,c768" */

static void say(const char *format, ...)
{
    char stamp[32];
    time_t now = time(NULL);
    va_list list;

    strftime(stamp, sizeof stamp, "%H:%M:%S", localtime(&now));
    fprintf(stderr, "[llroot %s] ", stamp);
    va_start(list, format);
    vfprintf(stderr, format, list);
    va_end(list);
    fputc('\n', stderr);
}

/* the owner and the SELinux level of the tree */
static int look_at_rootfs(const char *path)
{
    struct stat st;
    char label[256];
    ssize_t n;
    const char *p = label;

    if (stat(path, &st) < 0 || !S_ISDIR(st.st_mode)) {
        say("%s: no such directory", path);
        return -1;
    }
    if (st.st_uid == 0) {
        say("%s belongs to root, not to an app", path);
        return -1;
    }
    app_uid = st.st_uid;
    app_gid = st.st_gid;
    n = getxattr(path, "security.selinux", label, sizeof label - 1);
    if (n <= 0) {
        say("%s has no SELinux label", path);
        return -1;
    }
    label[n] = 0;
    /* u:object_r:app_data_file:s0:c8,c257,c512,c768: the level is what follows the third colon */
    for (int colons = 0; *p && colons < 3; p++)
        if (*p == ':')
            colons++;
    snprintf(level, sizeof level, "%s", p);
    level[strcspn(level, "\n")] = 0;
    return *level ? 0 : -1;
}

static int read_line(int fd, char *buffer, size_t size)
{
    size_t length = 0;

    while (length + 1 < size) {
        char c;
        ssize_t n = read(fd, &c, 1);

        if (n <= 0)
            return length ? (int) length : -1;
        if (c == '\n')
            break;
        buffer[length++] = c;
    }
    buffer[length] = 0;
    return (int) length;
}

static void write_line(int fd, const char *format, ...)
{
    char line[512];
    va_list list;
    int n;

    va_start(list, format);
    n = vsnprintf(line, sizeof line - 1, format, list);
    va_end(list);
    if (n < 0)
        return;
    if (n > (int) sizeof line - 2)
        n = sizeof line - 2;
    line[n++] = '\n';
    (void) !write(fd, line, n);
}

/* The process at the other end of a connection to 127.0.0.1:PORT, by /proc/net/tcp: its uid. */
static long peer_uid(int fd)
{
    struct sockaddr_in6 peer6;
    struct sockaddr_in *peer = (struct sockaddr_in *) &peer6;
    socklen_t size = sizeof peer6;
    unsigned port;
    const char *tables[] = {"/proc/net/tcp", "/proc/net/tcp6"};

    if (getpeername(fd, (struct sockaddr *) &peer6, &size) < 0)
        return -1;
    port = ntohs(peer6.sin6_family == AF_INET6 ? peer6.sin6_port : peer->sin_port);
    for (int t = 0; t < 2; t++) {
        FILE *f = fopen(tables[t], "re");
        char line[512];

        if (!f)
            continue;
        while (fgets(line, sizeof line, f)) {
            char local[64], remote[64];
            unsigned long uid;
            unsigned local_port, remote_port;
            char *colon;

            /* sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid */
            if (sscanf(line, "%*s %63s %63s %*s %*s %*s %*s %lu", local, remote, &uid) != 3)
                continue;
            colon = strrchr(local, ':');
            if (!colon || sscanf(colon + 1, "%x", &local_port) != 1)
                continue;
            colon = strrchr(remote, ':');
            if (!colon || sscanf(colon + 1, "%x", &remote_port) != 1)
                continue;
            if (local_port == port && remote_port == PORT) {
                fclose(f);
                return (long) uid;
            }
        }
        fclose(f);
    }
    return -1;
}

/* the supplementary groups of a running process of the app (the network is the group 3003) */
static int app_groups(gid_t *groups)
{
    DIR *proc = opendir("/proc");
    struct dirent *entry;
    int count = 0;

    while (proc && (entry = readdir(proc)) && count == 0) {
        char path[300], line[512];
        FILE *f;
        long uid = -1;

        if (entry->d_name[0] < '0' || entry->d_name[0] > '9')
            continue;
        snprintf(path, sizeof path, "/proc/%s/status", entry->d_name);
        if (!(f = fopen(path, "re")))
            continue;
        while (fgets(line, sizeof line, f)) {
            if (!strncmp(line, "Uid:", 4))
                uid = strtol(line + 4, NULL, 10);
            if (!strncmp(line, "Groups:", 7) && uid == (long) app_uid) {
                char *p = line + 7, *end;

                while (count < MAX_GROUPS) {
                    long gid = strtol(p, &end, 10);

                    if (end == p)
                        break;
                    groups[count++] = (gid_t) gid;
                    p = end;
                }
            }
        }
        fclose(f);
    }
    if (proc)
        closedir(proc);
    if (count == 0) {
        groups[count++] = 3003; /* inet */
        groups[count++] = 9997; /* everybody: the shared storage */
    }
    return count;
}

/* @inside (a path of the tree) as a path on Android, its symbolic links followed inside the tree:
 * /usr/bin/sudo is a link to /etc/alternatives/sudo, which is one to /usr/bin/sudo.ws */
static void tree_path(const char *inside, char *out, size_t size)
{
    char current[4096], target[4096], host[4096];

    snprintf(current, sizeof current, "%s", inside);
    for (int depth = 0; depth < 16; depth++) {
        struct stat st;
        ssize_t n;

        snprintf(host, sizeof host, "%s%s", rootfs, current);
        if (lstat(host, &st) < 0 || !S_ISLNK(st.st_mode))
            break;
        n = readlink(host, target, sizeof target - 1);
        if (n <= 0)
            break;
        target[n] = 0;
        if (target[0] == '/') {
            snprintf(current, sizeof current, "%s", target);
        } else {
            char *slash = strrchr(current, '/');
            char joined[4096];

            snprintf(joined, sizeof joined, "%.*s/%s", slash ? (int) (slash - current) : 0, current, target);
            snprintf(current, sizeof current, "%s", joined);
        }
    }
    snprintf(out, size, "%s%s", rootfs, current);
}

static int bind_into(const char *from, const char *to, int recursive)
{
    char target[4096];
    struct stat st;

    snprintf(target, sizeof target, "%s%s", rootfs, to);
    if (stat(from, &st) < 0 || stat(target, &st) < 0)
        return -1;
    if (mount(from, target, NULL, MS_BIND | (recursive ? MS_REC : 0), NULL) < 0) {
        say("mount %s on %s: %s", from, target, strerror(errno));
        return -1;
    }
    return 0;
}

/* A line for @id in /etc/passwd or /etc/group of the tree, added at the end when there is none. */
static void ensure_entry(const char *file, unsigned id, const char *entry)
{
    char path[4096], line[1024], wanted[32];
    FILE *f;
    int found = 0;

    snprintf(path, sizeof path, "%s/etc/%s", rootfs, file);
    snprintf(wanted, sizeof wanted, ":%u:", id);
    if (!(f = fopen(path, "re")))
        return;
    while (!found && fgets(line, sizeof line, f)) {
        /* name:x:id: the third field */
        char *first = strchr(line, ':'), *second = first ? strchr(first + 1, ':') : NULL;

        found = second && strncmp(second, wanted, strlen(wanted)) == 0;
    }
    fclose(f);
    if (found)
        return;
    /* appended in place: the file keeps its owner and its label */
    if ((f = fopen(path, "ae"))) {
        fputs(entry, f);
        fclose(f);
        say("added to /etc/%s: %s", file, entry);
    }
}

static void ensure_user(void)
{
    char entry[256];

    snprintf(entry, sizeof entry, "honor:x:%u:%u:honor in the chroot mode:/home/honor:/bin/bash\n",
             (unsigned) app_uid, (unsigned) app_gid);
    ensure_entry("passwd", app_uid, entry);
    snprintf(entry, sizeof entry, "honor:x:%u:\n", (unsigned) app_gid);
    ensure_entry("group", app_gid, entry);
}

static void make_directories(const char *path)
{
    char partial[4096];

    for (const char *slash = strchr(path + 1, '/'); ; slash = strchr(slash + 1, '/')) {
        size_t length = slash ? (size_t) (slash - path) : strlen(path);

        snprintf(partial, sizeof partial, "%.*s", (int) length, path);
        mkdir(partial, 0755);
        if (!slash)
            break;
    }
}

/* the tree inside itself under its Android paths, on a tmpfs over its /data (see above) */
static void mount_android_paths(void)
{
    char data[4096], twin[4096], place[4096];
    const char *paths[2] = {rootfs, NULL};
    struct stat st;

    snprintf(data, sizeof data, "%s/data", rootfs);
    if (strncmp(rootfs, "/data/user/0/", 13) == 0) {
        snprintf(twin, sizeof twin, "/data/data/%s", rootfs + 13);
        paths[1] = twin;
    } else if (strncmp(rootfs, "/data/data/", 11) == 0) {
        snprintf(twin, sizeof twin, "/data/user/0/%s", rootfs + 11);
        paths[1] = twin;
    }
    if (stat(data, &st) < 0 || !S_ISDIR(st.st_mode))
        return;
    if (mount("tmpfs", data, "tmpfs", MS_NOSUID | MS_NODEV, "mode=0755") < 0) {
        say("tmpfs on %s: %s", data, strerror(errno));
        return;
    }
    for (int i = 0; i < 2 && paths[i]; i++) {
        snprintf(place, sizeof place, "%s%s", rootfs, paths[i]);
        make_directories(place);
        if (mount(rootfs, place, NULL, MS_BIND, NULL) < 0)
            say("mount %s on %s: %s", rootfs, place, strerror(errno));
    }
}

/*
 * /dev/dri is hidden as under PRoot, which the app cannot see it from. It is the display controller
 * of the tablet (msm_drm) and holds no GPU: Mutter, seeing it, made it its renderer, zink found no
 * Vulkan device on it, and GNOME Shell came up slowly, its calls timing out, and drew badly. The GPU is
 * /dev/kgsl-3d0, which Mesa's turnip uses.
 */
static void hide_dri(void)
{
    char dri[4096];

    snprintf(dri, sizeof dri, "%s/dev/dri", rootfs);
    if (access(dri, F_OK) == 0 && mount("tmpfs", dri, "tmpfs", MS_NOSUID | MS_NODEV | MS_NOEXEC, "mode=0755") < 0)
        say("tmpfs on %s: %s", dri, strerror(errno));
}

/* The desktop: a namespace of mounts, the tree as root, the app's user. Does not return. */
static void enter_and_run(const char *desktop, char **environment)
{
    gid_t groups[MAX_GROUPS];
    int group_count = app_groups(groups);
    char shm[4096], tmp[4096], sudo[4096], wrapper[4096];
    struct stat st;
    const char *command = strcmp(desktop, "gnome") == 0 ? "/opt/linuxlab-gnome/gnome-session.sh" : "/usr/bin/startlxqt";
    char *argv[] = {(char *) command, NULL};

    if (unshare(CLONE_NEWNS) < 0 || mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) < 0) {
        say("a namespace of mounts: %s", strerror(errno));
        _exit(120);
    }
    bind_into("/proc", "/proc", 0);
    bind_into("/dev", "/dev", 1);
    bind_into("/dev/pts", "/dev/pts", 0);
    hide_dri();
    bind_into("/sys", "/sys", 1);
    bind_into("/storage/emulated/0", "/tablet", 0);
    /* POSIX shared memory, on the /tmp of the tree as under PRoot; /dev of Android has no shm */
    mkdir("/dev/shm", 0755);
    snprintf(shm, sizeof shm, "%s/dev/shm", rootfs);
    snprintf(tmp, sizeof tmp, "%s/tmp", rootfs);
    if (mount(tmp, shm, NULL, MS_BIND, NULL) < 0)
        say("mount %s on %s: %s", tmp, shm, strerror(errno));
    mount_android_paths();
    snprintf(wrapper, sizeof wrapper, "%s/opt/linuxlab-gnome/sudo-chroot", rootfs);
    tree_path("/usr/bin/sudo", sudo, sizeof sudo);
    if (stat(wrapper, &st) == 0 && lstat(sudo, &st) == 0 && S_ISREG(st.st_mode) &&
        mount(wrapper, sudo, NULL, MS_BIND, NULL) < 0)
        say("mount %s on %s: %s", wrapper, sudo, strerror(errno));

    if (chroot(rootfs) < 0 || chdir("/") < 0) {
        say("chroot %s: %s", rootfs, strerror(errno));
        _exit(121);
    }
    if (setgroups(group_count, groups) < 0 || setresgid(app_gid, app_gid, app_gid) < 0 ||
        setresuid(app_uid, app_uid, app_uid) < 0) {
        say("becoming %u: %s", (unsigned) app_uid, strerror(errno));
        _exit(122);
    }
    if (getuid() == 0 || geteuid() == 0)
        _exit(123);
    if (chdir("/home/honor") < 0)
        (void) !chdir("/");
    setsid();
    execve(command, argv, environment);
    say("%s: %s", command, strerror(errno));
    _exit(127);
}

static int same_namespace(pid_t pid, const char *namespace)
{
    char path[64], link[128];
    ssize_t n;

    snprintf(path, sizeof path, "/proc/%d/ns/mnt", (int) pid);
    n = readlink(path, link, sizeof link - 1);
    if (n <= 0)
        return 0;
    link[n] = 0;
    return strcmp(link, namespace) == 0;
}

/* every process left in the namespace of the desktop; how many there were */
static int signal_namespace(const char *namespace, int sig)
{
    DIR *proc = opendir("/proc");
    struct dirent *entry;
    int count = 0;

    while (proc && (entry = readdir(proc))) {
        pid_t pid = (pid_t) atoi(entry->d_name);

        if (pid > 1 && pid != getpid() && same_namespace(pid, namespace)) {
            kill(pid, sig);
            count++;
        }
    }
    if (proc)
        closedir(proc);
    return count;
}

/* One connection: hello, or a desktop from start to end. Runs in a process of its own. */
static void serve_client(int fd)
{
    char line[8192];
    char *environment[MAX_ENV + 1];
    int count = 0;
    long uid = peer_uid(fd);
    pid_t child;
    char namespace[128] = "";
    int status = 0, stopping = 0;
    time_t stop_at = 0;

    while (read_line(fd, line, sizeof line) >= 0) {
        if (!strcmp(line, "hello")) {
            write_line(fd, "LLROOT 1 %u %s", (unsigned) app_uid, level);
            continue;
        }
        if (uid != (long) app_uid) {
            write_line(fd, "error only the user of Linux Lab may do that");
            say("refused a request from uid %ld", uid);
            return;
        }
        if (!strncmp(line, "env ", 4)) {
            /* not the variables of PRoot, nor a preload meant for it */
            if (count < MAX_ENV - 1 && strncmp(line + 4, "PROOT_", 6) != 0 && strncmp(line + 4, "LD_PRELOAD=", 11) != 0 &&
                strncmp(line + 4, "LINUXLAB_ENGINE=", 16) != 0)
                environment[count++] = strdup(line + 4);
            continue;
        }
        if (!strncmp(line, "run ", 4))
            break;
        write_line(fd, "error what");
    }
    if (strncmp(line, "run ", 4) != 0)
        return;
    if (strcmp(line + 4, "gnome") != 0 && strcmp(line + 4, "lxqt") != 0) {
        write_line(fd, "error no such desktop");
        return;
    }
    /* for the scripts of the desktop: they cannot tell otherwise that sudo is fakeroot here */
    environment[count++] = strdup("LINUXLAB_ENGINE=chroot");
    environment[count] = NULL;
    ensure_user();

    child = fork();
    if (child == 0) {
        close(fd);
        enter_and_run(line + 4, environment);
    }
    if (child < 0) {
        write_line(fd, "error fork: %s", strerror(errno));
        return;
    }
    say("%s started, process %d", line + 4, (int) child);
    write_line(fd, "started %d", (int) child);
    /* the namespace of the child, to find all of the desktop later */
    for (int i = 0; i < 50 && !*namespace; i++) {
        char path[64];
        char mine[128] = "";
        ssize_t n;

        snprintf(path, sizeof path, "/proc/%d/ns/mnt", (int) child);
        n = readlink(path, namespace, sizeof namespace - 1);
        namespace[n > 0 ? n : 0] = 0;
        n = readlink("/proc/self/ns/mnt", mine, sizeof mine - 1);
        mine[n > 0 ? n : 0] = 0;
        if (*namespace && strcmp(namespace, mine) == 0)
            *namespace = 0; /* not unshared yet */
        if (!*namespace)
            usleep(20000);
    }

    for (;;) {
        if (waitpid(child, &status, WNOHANG) == child)
            break;
        if (!stopping) {
            struct pollfd poll_fd = {fd, POLLIN, 0};

            if (poll(&poll_fd, 1, 200) > 0 && (read_line(fd, line, sizeof line) < 0 || !strcmp(line, "stop"))) {
                say("told to stop");
                stopping = 1;
                stop_at = time(NULL) + 10;
                kill(-child, SIGTERM);
                kill(child, SIGTERM);
            }
        } else {
            usleep(200000);
            if (time(NULL) >= stop_at && *namespace) {
                say("the desktop did not end in ten seconds");
                signal_namespace(namespace, SIGKILL);
                stop_at = time(NULL) + 10;
            }
        }
    }
    /* what the desktop left behind: buses, daemons that went into the background */
    if (*namespace && signal_namespace(namespace, SIGTERM) > 0) {
        for (int i = 0; i < 25 && signal_namespace(namespace, 0) > 0; i++)
            usleep(200000);
        signal_namespace(namespace, SIGKILL);
    }
    status = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    say("the desktop is over: %d", status);
    write_line(fd, "exit %d", status);
}

static int serve(void)
{
    struct sockaddr_in address = {0};
    int server = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0), yes = 1;
    char log[4096];
    int out;

    address.sin_family = AF_INET;
    address.sin_port = htons(PORT);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);
    if (bind(server, (struct sockaddr *) &address, sizeof address) < 0) {
        if (errno == EADDRINUSE) {
            printf("running already\n");
            return 0;
        }
        say("bind: %s", strerror(errno));
        return 1;
    }
    listen(server, 4);

    /* into the background, with a log in the tree that the user of Linux Lab can read */
    printf("started\n");
    fflush(stdout);
    if (fork() > 0)
        _exit(0);
    setsid();
    if (fork() > 0)
        _exit(0);
    snprintf(log, sizeof log, "%s/var/log/linuxlab-chroot.log", rootfs);
    out = open(log, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (out >= 0) {
        (void) !fchown(out, app_uid, app_gid);
        dup2(out, 2);
        close(out);
    }
    out = open("/dev/null", O_RDWR);
    dup2(out, 0);
    dup2(out, 1);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_DFL);
    say("serving %s for uid %u at level %s", rootfs, (unsigned) app_uid, level);

    for (;;) {
        int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        pid_t worker;

        if (client < 0)
            continue;
        worker = fork();
        if (worker == 0) {
            close(server);
            serve_client(client);
            _exit(0);
        }
        close(client);
        while (waitpid(-1, NULL, WNOHANG) > 0)
            ;
    }
}

/*
 * The SELinux context: the domain of su (ksu, magisk, su), with the level of the app's files.
 * Changing it takes an exec, so the program runs itself again once.
 */
static int take_level(char **argv)
{
    char current[256], wanted[600];
    int fd = open("/proc/self/attr/current", O_RDONLY);
    ssize_t n = fd >= 0 ? read(fd, current, sizeof current - 1) : -1;
    char *domain_end;

    if (fd >= 0)
        close(fd);
    if (n <= 0)
        return 0; /* no SELinux */
    current[n] = 0;
    current[strcspn(current, "\n")] = 0;
    /* u:r:ksu:s0 */
    domain_end = strchr(current, ':');
    domain_end = domain_end ? strchr(domain_end + 1, ':') : NULL;
    domain_end = domain_end ? strchr(domain_end + 1, ':') : NULL;
    if (!domain_end)
        return 0;
    if (strcmp(domain_end + 1, level) == 0)
        return 0;
    *domain_end = 0;
    snprintf(wanted, sizeof wanted, "%s:%s", current, level);
    fd = open("/proc/thread-self/attr/exec", O_WRONLY);
    if (fd < 0 || write(fd, wanted, strlen(wanted)) < 0) {
        say("cannot ask for %s: %s", wanted, strerror(errno));
        if (fd >= 0)
            close(fd);
        return -1;
    }
    close(fd);
    execv("/proc/self/exe", argv);
    say("exec %s: %s", wanted, strerror(errno));
    return -1;
}

/*
 * su keeps the mount namespace of the app that called it, and Android shows an app no data of other
 * apps there: /data/user/0 holds only the helper itself. The namespace of init has everything.
 */
static void enter_global_namespace(void)
{
    int fd = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);

    if (fd < 0 || setns(fd, CLONE_NEWNS) < 0)
        say("the mount namespace of init: %s", strerror(errno));
    if (fd >= 0)
        close(fd);
}

int main(int argc, char **argv)
{
    if (argc != 3 || (strcmp(argv[1], "serve") != 0 && strcmp(argv[1], "check") != 0)) {
        fprintf(stderr, "usage: llroot serve|check <rootfs of Linux Lab>\n");
        return 2;
    }
    rootfs = argv[2];
    if (getuid() != 0) {
        say("not root");
        return 1;
    }
    enter_global_namespace();
    if (look_at_rootfs(rootfs) < 0)
        return 1;
    if (!strcmp(argv[1], "check")) {
        printf("ok %u %s\n", (unsigned) app_uid, level);
        return 0;
    }
    if (take_level(argv) < 0)
        return 1;
    return serve();
}
