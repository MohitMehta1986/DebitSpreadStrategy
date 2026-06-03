package debitspread;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple console logger with timestamps and levels.
 * Replace with SLF4J/Logback in production.
 */
public class Logger {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static boolean debugEnabled = false;

    public static void enableDebug() { debugEnabled = true; }

    public static void info(String msg)  { log("INFO ", msg); }
    public static void warn(String msg)  { log("WARN ", msg); }
    public static void error(String msg) { log("ERROR", msg); }
    public static void debug(String msg) { if (debugEnabled) log("DEBUG", msg); }

    private static void log(String level, String msg) {
        System.out.printf("[%s] [%s] %s%n",
            LocalDateTime.now().format(FMT), level, msg);
    }
}
