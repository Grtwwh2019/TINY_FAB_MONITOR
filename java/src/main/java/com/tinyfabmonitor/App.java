package com.tinyfabmonitor;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class App {
    private App() {}

    public static void main(String[] args) {
        Path base = baseDirectory();
        Path configPath = args.length >= 2 && "--config".equals(args[0]) ? Paths.get(args[1]).toAbsolutePath() : base.resolve("config.properties");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            AppConfig config = AppConfig.load(configPath, base);
            Files.createDirectories(config.storageDirectory);
            Logger logger = createLogger(config.storageDirectory.resolve("oracle-fab-monitor-java.log"));
            StateStore store = new StateStore(config.storageDirectory.resolve("state.json"));
            OracleRepository repository = new OracleRepository(config);
            MonitorService monitor = new MonitorService(config, repository, store, logger);
            SwingUtilities.invokeLater(() -> {
                final MainFrame[] holder = new MainFrame[1];
                holder[0] = new MainFrame(monitor, () -> {
                    monitor.close(); holder[0].dispose(); System.exit(0);
                });
                holder[0].setVisible(true);
                monitor.start();
            });
        } catch (Throwable error) {
            String message = rootMessage(error);
            writeStartupError(base.resolve("startup-error.txt"), message);
            try { JOptionPane.showMessageDialog(null, message + "\n\n详情已写入 startup-error.txt", "TINY FAB MONITOR 启动失败", JOptionPane.ERROR_MESSAGE); }
            catch (Throwable ignored) { System.err.println(message); }
            System.exit(1);
        }
    }

    private static Path baseDirectory() {
        try {
            URI location = App.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(location).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : path.getParent();
        } catch (Exception e) { return Paths.get("").toAbsolutePath().normalize(); }
    }

    private static Logger createLogger(Path path) throws IOException {
        Logger logger = Logger.getLogger("TinyFabMonitor"); logger.setUseParentHandlers(false);
        FileHandler handler = new FileHandler(path.toString(), true); handler.setEncoding("UTF-8"); handler.setFormatter(new SimpleFormatter()); logger.addHandler(handler);
        return logger;
    }

    private static void writeStartupError(Path path, String message) {
        String content = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + System.lineSeparator() + message + System.lineSeparator();
        try { Files.write(path, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
        catch (IOException ignored) {}
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + "：" + (current.getMessage() == null ? "未知错误" : current.getMessage());
    }
}
