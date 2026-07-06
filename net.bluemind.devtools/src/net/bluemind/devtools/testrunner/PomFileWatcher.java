package net.bluemind.devtools.testrunner;

import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

public class PomFileWatcher {

	private static final ILog LOG = Platform.getLog(PomFileWatcher.class);
	private static final long DEBOUNCE_MS = 500;
	private static final long STARTUP_DELAY_MS = 5000;

	private volatile WatchService watchService;
	private volatile Thread watcherThread;
	private volatile boolean running;
	private final AtomicBoolean starting = new AtomicBoolean();

	private static final PomFileWatcher INSTANCE = new PomFileWatcher();

	public static PomFileWatcher instance() {
		return INSTANCE;
	}

	public void start() {
		if (running || !starting.compareAndSet(false, true)) {
			return;
		}
		Job initJob = new Job("BlueMind POM Sync Init") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				if (!starting.get()) {
					return Status.CANCEL_STATUS;
				}
				try {
					PomSyncChecker.checkAndPrompt(false);

					if (starting.get()) {
						var pomPathOpt = PomPropertyReader.findGlobalPom();
						if (pomPathOpt.isPresent()) {
							startWatching(pomPathOpt.get());
						} else {
							LOG.info("No BlueMind global POM found, file watcher not started");
						}
					}
				} finally {
					if (!running) {
						starting.set(false);
					}
				}
				return Status.OK_STATUS;
			}
		};
		initJob.setSystem(true);
		initJob.schedule(STARTUP_DELAY_MS);
	}

	public void stop() {
		running = false;
		starting.set(false);
		try {
			if (watchService != null) {
				watchService.close();
				watchService = null;
			}
		} catch (Exception ignored) {
		}
		if (watcherThread != null) {
			watcherThread.interrupt();
			watcherThread = null;
		}
	}

	private void startWatching(Path pomPath) {
		Path pomDir = pomPath.getParent();
		String pomFileName = pomPath.getFileName().toString();

		try {
			watchService = FileSystems.getDefault().newWatchService();
			pomDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
					StandardWatchEventKinds.ENTRY_CREATE);

			running = true;
			watcherThread = new Thread(() -> watchLoop(pomFileName), "BlueMind-PomFileWatcher");
			watcherThread.setDaemon(true);
			watcherThread.start();
			LOG.info("Watching POM for changes: " + pomPath);
		} catch (Exception e) {
			LOG.error("Failed to start POM file watcher", e);
		}
	}

	private void watchLoop(String pomFileName) {
		while (running) {
			try {
				WatchKey key = watchService.take();
				boolean pomChanged = false;

				for (WatchEvent<?> event : key.pollEvents()) {
					if (event.context() instanceof Path changed
							&& pomFileName.equals(changed.getFileName().toString())) {
						pomChanged = true;
					}
				}
				key.reset();

				if (pomChanged) {
					// Debounce: wait and drain extra events (git switch generates multiple)
					Thread.sleep(DEBOUNCE_MS);
					WatchKey extra;
					while ((extra = watchService.poll()) != null) {
						extra.pollEvents();
						extra.reset();
					}

					LOG.info("POM file changed, checking sync...");
					PomSyncChecker.checkAndPrompt(false);
				}
			} catch (ClosedWatchServiceException ignored) {
				break;
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				LOG.error("Error in POM file watcher", e);
			}
		}
	}
}
