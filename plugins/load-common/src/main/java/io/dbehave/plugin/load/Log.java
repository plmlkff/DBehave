package io.dbehave.plugin.load;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Логи в stdout: их собирает Docker, отдельная библиотека не нужна. */
public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String message) {
        System.out.println(LocalTime.now().format(TIME) + " INFO  " + message);
    }

    public static void warn(String message) {
        System.out.println(LocalTime.now().format(TIME) + " WARN  " + message);
    }

    public static void error(String message, Throwable error) {
        System.out.println(LocalTime.now().format(TIME) + " ERROR " + message);
        if (error != null) {
            error.printStackTrace(System.out);
        }
    }
}
