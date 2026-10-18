package org.example.visiontrack.web;

import org.example.visiontrack.meeting.RecordingStateChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.RenderingHints;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "visiontrack.open-browser", havingValue = "true")
public class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private final boolean trayIcon;

    // Tray icon state, touched on the AWT event thread only
    private TrayIcon tray;
    private BufferedImage idleImage;
    private BufferedImage recordingImage;
    private Timer recordingTimer;

    public BrowserLauncher(Environment environment, ConfigurableApplicationContext context,
                           @Value("${visiontrack.tray-icon:false}") boolean trayIcon) {
        this.environment = environment;
        this.context = context;
        this.trayIcon = trayIcon;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser() {
        String url = "http://127.0.0.1:" + environment.getProperty("local.server.port", "8080");
        log.info("Hindsight is running at {}", url);
        if (trayIcon) addTrayIcon(url);
        open(url);
    }

    private void addTrayIcon(String url) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("System tray is not available");
            return;
        }
        try (InputStream image = getClass().getResourceAsStream("/tray-icon.png")) {
            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Open Hindsight");
            openItem.addActionListener(e -> open(url));
            MenuItem quitItem = new MenuItem("Quit");
            menu.add(openItem);
            menu.addSeparator();
            menu.add(quitItem);

            BufferedImage base = ImageIO.read(image);
            TrayIcon icon = new TrayIcon(base, "Hindsight", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> open(url));
            quitItem.addActionListener(e -> {
                SystemTray.getSystemTray().remove(icon);
                new Thread(() -> System.exit(SpringApplication.exit(context)), "quit").start();
            });
            SystemTray.getSystemTray().add(icon);
            EventQueue.invokeLater(() -> {
                tray = icon;
                idleImage = base;
                recordingImage = withRecordingDot(base);
            });
        } catch (Exception e) {
            log.warn("Could not add the tray icon: {}", e.getMessage());
        }
    }

    /** While a meeting is recorded the tray icon gets a red dot and the tooltip shows "● REC 00:12:34". */
    @EventListener
    public void onRecordingStateChanged(RecordingStateChanged event) {
        EventQueue.invokeLater(() -> {
            if (tray == null) return;
            if (recordingTimer != null) recordingTimer.stop();
            if (event.recording()) {
                tray.setImage(recordingImage);
                recordingTimer = new Timer(1000, e -> tray.setToolTip("Hindsight ● REC " + elapsed(event.startedAt())));
                recordingTimer.setInitialDelay(0);
                recordingTimer.start();
            } else {
                recordingTimer = null;
                tray.setImage(idleImage);
                tray.setToolTip("Hindsight");
            }
        });
    }

    private static String elapsed(Instant since) {
        long s = Math.max(0, Duration.between(since, Instant.now()).toSeconds());
        return "%02d:%02d:%02d".formatted(s / 3600, s / 60 % 60, s % 60);
    }

    private static BufferedImage withRecordingDot(BufferedImage base) {
        BufferedImage image = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(base, 0, 0, null);
        int d = Math.max(6, base.getWidth() / 2);
        int x = base.getWidth() - d, y = base.getHeight() - d;
        g.setColor(new Color(0xE5, 0x1C, 0x23));
        g.fillOval(x, y, d - 1, d - 1);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1f, d / 8f)));
        g.drawOval(x, y, d - 1, d - 1);
        g.dispose();
        return image;
    }

    public static void open(String url) {
        Optional<Path> browser = findChromiumBrowser();
        if (browser.isPresent() && openAppWindow(browser.get(), url)) return;
        openDefaultBrowser(url);
    }

    private static boolean openAppWindow(Path browser, String url) {
        Path profile = Path.of(System.getProperty("java.io.tmpdir"), "visiontrack-window");
        try {
            new ProcessBuilder(browser.toString(), "--app=" + url,
                    "--user-data-dir=" + profile, "--window-size=1100,900", "--no-first-run",
                    "--no-default-browser-check").start();
            return true;
        } catch (IOException e) {
            log.info("Could not open an app window with {} ({})", browser, e.getMessage());
            return false;
        }
    }

    private static Optional<Path> findChromiumBrowser() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> candidates;
        if (os.startsWith("windows")) {
            candidates = Stream.of(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA"))
                    .filter(dir -> dir != null)
                    .flatMap(dir -> Stream.of(Path.of(dir, "Microsoft/Edge/Application/msedge.exe"),
                            Path.of(dir, "Google/Chrome/Application/chrome.exe")))
                    .toList();
        } else if (os.startsWith("mac")) {
            candidates = List.of(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                    Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
        } else {
            candidates = Stream.of("google-chrome", "chromium", "chromium-browser", "microsoft-edge")
                    .flatMap(name -> Stream.of("/usr/bin/", "/snap/bin/").map(dir -> Path.of(dir + name)))
                    .toList();
        }
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }

    private static void openDefaultBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command = os.startsWith("windows") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                : os.startsWith("mac") ? new String[]{"open", url}
                : new String[]{"xdg-open", url};
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            log.info("Could not open a browser automatically ({}); open {} manually", e.getMessage(), url);
        }
    }
}
