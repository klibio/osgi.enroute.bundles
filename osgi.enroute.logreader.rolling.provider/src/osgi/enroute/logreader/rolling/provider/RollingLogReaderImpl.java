package osgi.enroute.logreader.rolling.provider;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;
import org.osgi.service.log.LogService;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import osgi.enroute.debug.api.Debug;
import osgi.enroute.logreader.rolling.provider.RollingLogReaderImpl.Config;

/**
 * Implements a {@link LogReader} service that persists {@link LogEvent}s to a
 * disk file and can be configured to perform a file roll over strategy based
 * upon a maximum file size (MB) policy. The maximum number of persisted log
 * files can also be specified. When the file size threshold has been reached
 * for the current log file a new log file will be created to replace it. After
 * file roll over, the previous log file will be closed and archived according
 * to the maximum number of log files policy parameter. Default behavior is to
 * perform a file roll over each time the service is activated.
 */
@Designate(ocd = Config.class)
@Component(name = "osgi.enroute.logreader.rolling", immediate = true, property = { Debug.COMMAND_SCOPE + "=rolling",
		Debug.COMMAND_FUNCTION + "=error", Debug.COMMAND_FUNCTION + "=warn", Debug.COMMAND_FUNCTION + "=info",
		Debug.COMMAND_FUNCTION + "=debug", Debug.COMMAND_FUNCTION + "=logfiles" })
public class RollingLogReaderImpl extends Thread implements LogListener {
	private static final int EXCEPTION_PAUSE_MILLIS = 1000;
	private static final int MAX_QUEUE_LENGTH = 100;
	private static final int MAX_WAIT_MILLIS = 2000;
	private static final int MAX_LOGFILES = 10;
	private static final int MAX_FILE_MEGABYTES = 1;

	private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(MAX_QUEUE_LENGTH);

	private File root;
	private Config config;

	@Reference
	private LogService log;

	/**
	 * Enumerated list of logging levels
	 */
	enum LogLevel {
		ERROR(LogService.LOG_ERROR), WARNING(LogService.LOG_WARNING), INFO(LogService.LOG_INFO), DEBUG(
				LogService.LOG_DEBUG);

		public final int level;

		/**
		 * Sets the logging level for this instance
		 * 
		 * @param level
		 *            the logging level threshold
		 */
		LogLevel(int level) {
			this.level = level;
		}
	}

	/**
	 * Service Properties for this service instance.
	 */
	@ObjectClassDefinition
	@interface Config {
		String root() default "messages";

		String format() default "%s %8s %s%n";

		int maxLogSizeMb() default MAX_FILE_MEGABYTES;

		int maxRetainedLogs() default MAX_LOGFILES;

		LogLevel level() default LogLevel.DEBUG;

		boolean pipeToConsole() default false;

		boolean purgeOnStart() default false;
	}

	/**
	 * Called when this service instance has been activated
	 */
	@Activate
	void activate(BundleContext context, Config config) {
		this.config = config;
		File f = new File(config.root());
		if (f.isAbsolute()) {
			root = f;
		} else {
			root = context.getDataFile(config.root());
			if (root == null) {
				throw new IllegalStateException("File system support is not available, cannot create file object");
			}
		}
		if (root.mkdirs()) {
			if (!root.isDirectory()) {
				throw new IllegalStateException("Cannot create directory " + root);
			}
			start();
		} else {
			throw new IllegalStateException("Cannot not create directory " + root);
		}
	}

	/**
	 * Called when this service instance has been deactivated
	 */
	@Deactivate
	void deactivate() throws InterruptedException {
		// Interrupt the blocking queue
		// consumer's thread
		interrupt();
		join(MAX_WAIT_MILLIS);
	}

	/**
	 * Listener method called for each LogEntry object created
	 * 
	 * @see org.osgi.service.log.LogListener#logged(org.osgi.service.log.LogEntry)
	 * @param entry
	 *            the LogEntry instance to log
	 */
	@Override
	public void logged(LogEntry entry) {
		if (entry.getLevel() > config.level().level) {
			return;
		}
		queue.offer(entry);
	}

	/**
	 * Overrides the Thread run method
	 * 
	 * @see java.lang.Thread#run()
	 */
	@Override
	public void run() {
		try {
			RandomAccessFile file = rollover();
			long limit = (config.maxLogSizeMb() * (1024 * 1024));

			while (!isInterrupted()) {
				LogEntry entry = queue.take();

				String log = String.format(config.format(), getFormattedDate(true), level(entry.getLevel()),
						entry.getMessage(), entry.getException(), entry.getBundle().getBundleId());

				if (config.pipeToConsole()) {
					System.out.print(log);
				}

				if ((file.length() + log.length()) > limit) {
					file.close();
					file = rollover();
					// Enqueue the last dequeued LogEntry to prevent loss
					queue.offer(entry);
				} else {
					file.write(log.getBytes(StandardCharsets.UTF_8));
				}
			}
		} catch (InterruptedException e) {
			return;
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
			try {
				Thread.sleep(EXCEPTION_PAUSE_MILLIS);
			} catch (InterruptedException e1) {
				return;
			}
		}
	}

	/**
	 * Calculates next available log file name and performs a roll over
	 * 
	 * @return new log file
	 */
	private RandomAccessFile rollover() throws IOException {
		String name = getFormattedDate(false);
		File f = new File(root, name + ".log");

		RandomAccessFile raf = new RandomAccessFile(f, "rwd");
		raf.seek(raf.length());
		raf.write("# next file\n".getBytes(StandardCharsets.UTF_8));

		System.out.println("Rolling over to '" + f + "'");
		purge();

		return raf;
	}

	/**
	 * Gets the current time stamp suitable for use in log messages
	 * 
	 * @param addZoneOffset
	 *            specifies whether to append the zone offset to the time stamp
	 * @return formatted current local date time with optional zone offset
	 */
	private String getFormattedDate(boolean addZoneOffset) {
		if (addZoneOffset) {
			DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSZ");
			return ZonedDateTime.now().format(FORMATTER);
		} else {
			return DateTimeFormatter.ISO_DATE_TIME.format(LocalDateTime.now());
		}
	}

	/**
	 * Deletes persisted log files according to the max log files policy
	 * configuration
	 */
	private void purge() {
		Stream.of(root.listFiles()).sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified()))
				.skip(config.maxRetainedLogs()).forEach(ff -> ff.delete());
	}

	/**
	 * Sets the logging level for this service instance
	 * 
	 * @param level
	 *            the logging level for this instance
	 */
	private String level(int level) {
		switch (level) {
		case LogService.LOG_DEBUG:
			return "debug";
		case LogService.LOG_INFO:
			return " info";
		case LogService.LOG_WARNING:
			return " warn";
		case LogService.LOG_ERROR:
			return "error";
		default:
			return "?????" + level;
		}
	}

	/**
	 * Sets the LogReader for this service instance
	 * 
	 * @param lsr
	 *            the OSGi LogReaderService
	 */
	@Reference
	void setLogReader(LogReaderService lsr) {
		if (lsr != null) {
			lsr.addLogListener(this);
		}
	}

	/**
	 * Emits an error message to the log file
	 * 
	 * @param args
	 *            the list of arguments to be used in the logged message
	 */
	public void error(String... args) {
		if (args != null) {
			log.log(LogService.LOG_ERROR, Stream.of(args).reduce("", (a, b) -> a + " " + b));
		}
	}

	/**
	 * Emits a warning message to the log file
	 * 
	 * @param args
	 *            the list of arguments to be used in the logged message
	 */
	public void warn(String... args) {
		if (args != null) {
			log.log(LogService.LOG_WARNING, Stream.of(args).reduce("", (a, b) -> a + " " + b));
		}
	}

	/**
	 * Emits an informational message to the log file
	 * 
	 * @param args
	 *            the list of arguments to be used in the logged message
	 */
	public void info(String... args) {
		if (args != null) {
			log.log(LogService.LOG_INFO, Stream.of(args).reduce("", (a, b) -> a + " " + b));
		}
	}

	/**
	 * Emits a debug message to the log file
	 * 
	 * @param args
	 *            the list of arguments to be used in the logged message
	 */
	public void debug(String... args) {
		if (args != null) {
			log.log(LogService.LOG_DEBUG, Stream.of(args).reduce("", (a, b) -> a + " " + b));
		}
	}

	/**
	 * Gets the list of persistent log files since last log file
	 * {@link #purge()} was executed
	 * 
	 * @return array of log files
	 */
	public File[] logfiles() {
		return (root != null) ? root.listFiles() : null;
	}
}
