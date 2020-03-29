package edu.ftp.client;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.logging.*;

/**
 * Stream Logger for all implemented classes.
 * Using {@link java.util.logging.ConsoleHandler} by default.
 */
public interface StreamLogging {
    Logger logger = Logger.getLogger("FTP");
    DateFormat dateFormatter = new SimpleDateFormat("YYYY-MM-DD HH:mm:ss");
    Formatter logFormatter = new Formatter() {
        @Override
        public String format(LogRecord record) {
            String[] sourceClass = record.getSourceClassName().split("\\.");
            return String.format("%s [%s] <%s> %s",
                    dateFormatter.format(Calendar.getInstance().getTime()),
                    record.getLevel(),
                    sourceClass[sourceClass.length - 1],
                    record.getMessage());
        }
    };

    /**
     * Add publisher for {@link StreamLogging} interface.
     * If you simply want to log in console with prefix, try
     * <pre><code>
     * StreamLogging.addLogPublisher((String record)-{@literal >}{
     *      System.out.println("MyLog: " + record);
     * });
     * </code></pre>
     * or the old-fashioned way,
     * <pre><code>
     * StreamLogging.addLogPublisher(new StreamLoggingPublisher(){
     *      {@literal @}Override
     *      void publish(String logRecord){
     *          System.out.println("MyLog: " + record);
     *      }
     * });
     * </code></pre>
     *
     * @param streamLoggingPublisher Publisher for logs.
     * @see StreamLoggingPublisher
     */
    static void addLogPublisher(StreamLoggingPublisher streamLoggingPublisher) {
        logger.setUseParentHandlers(false);
        logger.addHandler(new StreamLoggingHandler() {
            @Override
            public void publish(LogRecord logRecord) {
                streamLoggingPublisher.publish(logFormatter.format(logRecord));
            }
        });
    }
}

abstract class StreamLoggingHandler extends Handler {
    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }
}

