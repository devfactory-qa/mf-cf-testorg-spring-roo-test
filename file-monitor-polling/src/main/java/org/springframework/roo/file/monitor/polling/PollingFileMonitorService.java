package org.springframework.roo.file.monitor.polling;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.springframework.roo.file.monitor.DirectoryMonitoringRequest;
import org.springframework.roo.file.monitor.FileMonitorService;
import org.springframework.roo.file.monitor.MonitoringRequest;
import org.springframework.roo.file.monitor.NotifiableFileMonitorService;
import org.springframework.roo.file.monitor.event.FileDetails;
import org.springframework.roo.file.monitor.event.FileEvent;
import org.springframework.roo.file.monitor.event.FileEventListener;
import org.springframework.roo.file.monitor.event.FileOperation;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.FileUtils;

/**
 * A simple polling-based {@link FileMonitorService}.
 *
 * <p>
 * This implementation iterates over each of the {@link MonitoringRequest} instances,
 * building an active file index at the time of execution. It then compares this active file
 * index with the last time it was executed for that particular {@link MonitoringRequest}.
 * Events are then fired, and only when the event firing process has completed is the next
 * {@link MonitoringRequest} examined.
 *
 * <p>
 * This implementation does not recognize {@link FileOperation#RENAMED} events. This implementation
 * will ignore any monitored files with a filename starting with a period (ie hidden files).
 *
 * <p>
 * In the case of {@link FileOperation#DELETED} events, this implementation will present in the
 * {@link FileEvent} times equal to the last time a deleted file was
 * modified. The time does NOT represent the deletion time nor the time the deletion was first
 * detected.
 *
 * @author Ben Alex
 * @since 1.0
 */
public class PollingFileMonitorService implements NotifiableFileMonitorService {
	private final Set<FileEventListener> fileEventListeners = new HashSet<FileEventListener>();
	private final Set<MonitoringRequest> requests = new LinkedHashSet<MonitoringRequest>();
	private final Map<MonitoringRequest, Map<File, Long>> priorExecution = new WeakHashMap<MonitoringRequest, Map<File, Long>>();
	private final Set<String> notifyChanged = new HashSet<String>();
	private final Set<String> notifyCreated = new HashSet<String>();
	private final Set<String> notifyDeleted = new HashSet<String>();
	private final Map<String, Set<String>> changeMap = new HashMap<String, Set<String>>();
	private final Set<String> allFiles = new HashSet<String>();

	// Mutex
	private final Object lock = new Object();

	public final void add(final FileEventListener e) {
		synchronized (lock) {
			fileEventListeners.add(e);
		}
	}

	public final void remove(final FileEventListener e) {
		synchronized (lock) {
			fileEventListeners.remove(e);
		}
	}

	public List<FileDetails> getMonitored() {
		synchronized (lock) {
			final List<FileDetails> monitored = new ArrayList<FileDetails>();
			if (requests.isEmpty()) {
				return monitored;
			}

			for (final MonitoringRequest request : requests) {
				if (priorExecution.containsKey(request)) {
					final Map<File, Long> priorFiles = priorExecution.get(request);
					for (final Entry<File, Long> entry : priorFiles.entrySet()) {
						monitored.add(new FileDetails(entry.getKey(), entry.getValue()));
					}
				}
			}

			return monitored;
		}
	}

	public boolean isDirty() {
		synchronized (lock) {
			return !notifyChanged.isEmpty() || !notifyCreated.isEmpty() || !notifyDeleted.isEmpty();
		}
	}

	public Collection<String> getDirtyFiles(final String requestingClass) {
		synchronized (lock) {
			Collection<String> changesSinceLastRequest = changeMap.get(requestingClass);
			if (changesSinceLastRequest == null) {
				changesSinceLastRequest = new LinkedHashSet<String>(allFiles);
				changeMap.put(requestingClass, new LinkedHashSet<String>());
			} else {
				final Set<String> copyOfChangesSinceLastRequest = new LinkedHashSet<String>(changesSinceLastRequest);
				changesSinceLastRequest.removeAll(copyOfChangesSinceLastRequest);
				changesSinceLastRequest = copyOfChangesSinceLastRequest;
			}
			return changesSinceLastRequest;
		}
	}

	private boolean isWithin(final MonitoringRequest request, final String filePath) {
		String requestCanonicalPath;
		try {
			requestCanonicalPath = request.getFile().getCanonicalPath();
		} catch (final IOException e) {
			return false;
		}
		if (request instanceof DirectoryMonitoringRequest) {
			final DirectoryMonitoringRequest dmr = (DirectoryMonitoringRequest) request;
			if (dmr.isWatchSubtree()) {
				if (!filePath.startsWith(requestCanonicalPath)) {
					return false; // Not within this directory or a sub-directory
				}
			} else {
				if (!FileUtils.matchesAntPath(requestCanonicalPath + File.separator + "*", filePath)) {
					return false; // Not within this directory
				}
			}
		} else {
			if (!requestCanonicalPath.equals(filePath)) {
				return false; // Not a file
			}
		}
		return true;
	}

	public int scanNotified() {
		synchronized (lock) {
			if (noRequestsOrChanges()) {
				return 0;
			}
			return publishRequestedFileEvents();
		}
	}
	
	private boolean noRequestsOrChanges() {
		return requests.isEmpty() || !isDirty();
	}

	private int publishRequestedFileEvents() {
		int eventsPublished = 0;
		for (final MonitoringRequest request : requests) {
			final List<FileEvent> eventsToPublish = new ArrayList<FileEvent>();
			
			// See when each file was last checked
			Map<File, Long> priorFiles = priorExecution.get(request);
			if (priorFiles == null) {
				priorFiles = new HashMap<File, Long>();
				priorExecution.put(request, priorFiles);
			}

			// Handle files apparently updated, created, or deleted since the last execution
			eventsToPublish.addAll(getFileUpdateEvents(request, priorFiles));
			eventsToPublish.addAll(getFileCreationEvents(request, priorFiles));
			eventsToPublish.addAll(getFileDeletionEvents(request, priorFiles));
			
			publish(eventsToPublish);
			eventsPublished += eventsToPublish.size();
		}
		return eventsPublished;
	}

	private List<FileEvent> getFileUpdateEvents(final MonitoringRequest request, final Map<File, Long> priorFiles) {
		final List<FileEvent> updateEvents = new ArrayList<FileEvent>();
		for (final Iterator<String> iter = notifyChanged.iterator(); iter.hasNext();) {
			final String filePath = iter.next();
			if (isWithin(request, filePath)) {
				iter.remove();	// we've processed it
				// Skip this file if it doesn't exist
				final File thisFile = new File(filePath);
				if (thisFile.exists()) {
					// Record the notification
					updateEvents.add(new FileEvent(new FileDetails(thisFile, thisFile.lastModified()), FileOperation.UPDATED, null));
					// Update the prior execution map so it isn't notified again next round
					priorFiles.put(thisFile, thisFile.lastModified());
					// Also remove it from the created list, if it's in there
					if (notifyCreated.contains(filePath)) {
						notifyCreated.remove(filePath);
					}
				}
			}
		}
		return updateEvents;
	}
	
	private List<FileEvent> getFileCreationEvents(final MonitoringRequest request, final Map<File, Long> priorFiles) {
		final List<FileEvent> createEvents = new ArrayList<FileEvent>();
		for (final Iterator<String> iter = notifyCreated.iterator(); iter.hasNext();) {
			final String filePath = iter.next();
			if (isWithin(request, filePath)) {
				iter.remove();	// we've processed it
				// Skip this file if it doesn't exist
				final File thisFile = new File(filePath);
				if (thisFile.exists()) {
					// Record the notification
					createEvents.add(new FileEvent(new FileDetails(thisFile, thisFile.lastModified()), FileOperation.CREATED, null));
					// Update the prior execution map so it isn't notified again next round
					priorFiles.put(thisFile, thisFile.lastModified());
				}
			}
		}
		return createEvents;
	}
	
	private List<FileEvent> getFileDeletionEvents(final MonitoringRequest request, final Map<File, Long> priorFiles) {
		final List<FileEvent> deleteEvents = new ArrayList<FileEvent>();
		for (final Iterator<String> iter = notifyDeleted.iterator(); iter.hasNext();) {
			final String filePath = iter.next();
			if (isWithin(request, filePath)) {
				iter.remove();	// we've processed it
				// Skip this file if it suddenly exists again (it shouldn't be in the notify deleted in this case!)
				final File thisFile = new File(filePath);
				if (!thisFile.exists()) {
					// Record the notification
					deleteEvents.add(new FileEvent(new FileDetails(thisFile, null), FileOperation.DELETED, null));
					// Update the prior execution map so it isn't notified again next round
					priorFiles.remove(thisFile);
				}
			}
		}
		return deleteEvents;
	}
	
	public int scanAll() {
		synchronized (lock) {
			if (requests.isEmpty()) {
				return 0;
			}

			int changes = 0;

			for (final MonitoringRequest request : requests) {
				boolean includeSubtree = false;
				if (request instanceof DirectoryMonitoringRequest) {
					includeSubtree = ((DirectoryMonitoringRequest)request).isWatchSubtree();
				}

				if (!request.getFile().exists()) {
					continue;
				}

				// Build contents of the monitored location
				final Map<File,Long> currentExecution = new HashMap<File,Long>();
				computeEntries(currentExecution, request.getFile(), includeSubtree);

				final List<FileEvent> eventsToPublish = new ArrayList<FileEvent>();

				if (priorExecution.containsKey(request)) {
					// Need to perform a comparison, as we have data from a previous execution
					final Map<File,Long> priorFiles = priorExecution.get(request);

					// Locate created and modified files
					for (final Entry<File, Long> entry : currentExecution.entrySet()) {
						final File thisFile = entry.getKey();
						final Long currentTimestamp = entry.getValue();
						if (!priorFiles.containsKey(thisFile)) {
							// This file did not exist last execution, so it must be new
							eventsToPublish.add(new FileEvent(new FileDetails(thisFile, currentTimestamp), FileOperation.CREATED, null));
							try {
								// If this file was already going to be notified, there is no need to do it twice
								notifyCreated.remove(thisFile.getCanonicalPath());
							} catch (final IOException ignored) {}
							continue;
						}

						final Long previousTimestamp = priorFiles.get(thisFile);
						if (!currentTimestamp.equals(previousTimestamp)) {
							// Modified
							eventsToPublish.add(new FileEvent(new FileDetails(thisFile, currentTimestamp), FileOperation.UPDATED, null));
							try {
								// If this file was already going to be notified, there is no need to do it twice
								notifyChanged.remove(thisFile.getCanonicalPath());
							} catch (final IOException ignored) {}
						}
					}

					// Now locate deleted files
					priorFiles.keySet().removeAll(currentExecution.keySet());
					for (final Entry<File, Long> entry : priorFiles.entrySet()) {
						final File deletedFile = entry.getKey();
						eventsToPublish.add(new FileEvent(new FileDetails(deletedFile, entry.getValue()), FileOperation.DELETED, null));
						try {
							// If this file was already going to be notified, there is no need to do it twice
							notifyDeleted.remove(deletedFile.getCanonicalPath());
						} catch (final IOException ignored) {}
					}
				} else {
					// No data from previous execution, so it's a newly-monitored location
					for (final Entry<File, Long> entry : currentExecution.entrySet()) {
						eventsToPublish.add(new FileEvent(new FileDetails(entry.getKey(), entry.getValue()), FileOperation.MONITORING_START, null));
					}
				}

				// Record the monitored location's contents, ready for next execution
				priorExecution.put(request, currentExecution);

				// We can discard the created and deleted notifications, as they would have been correctly discovered in the above loop
				notifyCreated.clear();
				notifyDeleted.clear();

				// Explicitly handle any undiscovered update notifications, as this indicates an identical millisecond update occurred
				for (final String canonicalPath : notifyChanged) {
					final File file = new File(canonicalPath);
					eventsToPublish.add(new FileEvent(new FileDetails(file, file.lastModified()), FileOperation.UPDATED, null));
				}
				notifyChanged.clear();
				publish(eventsToPublish);

				changes += eventsToPublish.size();
			}

			return changes;
		}
	}

	/**
	 * Publish the events, if needed.
	 *
	 * <p>
	 * This method assumes the caller has already acquired a synchronisation lock.
	 *
	 * @param eventsToPublish to publish (not null, but can be empty)
	 */
	private void publish(final List<FileEvent> eventsToPublish) {
		if (eventsToPublish.isEmpty()) {
			return;
		}
		if (fileEventListeners.isEmpty() || eventsToPublish.isEmpty()) {
			return;
		}
		for (final FileEvent event : eventsToPublish) {
			updateChanges(event.getFileDetails().getCanonicalPath(), event.getOperation() == FileOperation.DELETED);
			for (final FileEventListener l : fileEventListeners) {
				l.onFileEvent(event);
			}
		}
	}

	/**
	 * Adds one or more entries into the Map. The key of the Map is the File object, and the value
	 * is the {@link File#lastModified()} time.
	 *
	 * <p>
	 * Specifically:
	 *
	 * <ul>
	 * <li>If invoked with a File that is actually a File, only the file is added.</li>
	 * <li>If invoked with a File that is actually a Directory, all files and directories are added.</li>
	 * <li>If invoked with a File that is actually a Directory, subdirectories will be added only if
	 * "includeSubtree" is true.</li>
	 * </ul>
	 */
	private void computeEntries(final Map<File, Long> map, final File currentFile, final boolean includeSubtree) {
		Assert.notNull(map, "Map required");
		Assert.notNull(currentFile, "Current file is required");

		if (!currentFile.exists() || (currentFile.getName().length() > 1 && currentFile.getName().startsWith("."))) {
			return;
		}

		map.put(currentFile, currentFile.lastModified());

		try {
			updateAllFiles(currentFile.getCanonicalPath(), false);
		} catch (final IOException ignored) {}

		if (currentFile.isDirectory()) {
			final File[] files = currentFile.listFiles();
			if (files == null || files.length == 0) return;
			for (final File file : files) {
				if (file.isFile() || includeSubtree) {
					computeEntries(map, file, includeSubtree);
				}
			}
		}
	}

	public boolean add(final MonitoringRequest request) {
		synchronized (lock) {
			Assert.notNull(request, "MonitoringRequest required");

			// Ensure existing monitoring requests don't overlap with this new request;
			// amend existing requests or ignore new request as appropriate
			if (request instanceof DirectoryMonitoringRequest) {
				final DirectoryMonitoringRequest dmr = (DirectoryMonitoringRequest) request;
				if (dmr.isWatchSubtree()) {
					for (final MonitoringRequest existing : requests) {
						if (existing instanceof DirectoryMonitoringRequest) {
							final DirectoryMonitoringRequest existingDmr = (DirectoryMonitoringRequest) existing;
							if (existingDmr.isWatchSubtree()) {
								// We have a new request and an existing request, both for directories, and both which monitor sub-trees
								String existingDmrPath;
								String newDmrPath;
								try {
									existingDmrPath = existingDmr.getFile().getCanonicalPath();
									newDmrPath = dmr.getFile().getCanonicalPath();
								} catch (final IOException ioe) {
									throw new IllegalStateException("Unable to resolve canonical name", ioe);
								}
								// If the new request is a sub-directory of the existing request, ignore the new request as it's unnecessary
								if (newDmrPath.startsWith(existingDmrPath)) {
									return false;
								}
								// If the existing request is a sub-directory of the new request, remove the existing request as this new request
								// will incorporate it
								if (existingDmrPath.startsWith(newDmrPath)) {
									remove(existing);
								}
							}
						}
					}
				}
			}

			return requests.add(request);
		}
	}

	public boolean remove(final MonitoringRequest request) {
		synchronized (lock) {
			Assert.notNull(request, "MonitoringRequest required");

			// Advise of the cessation to monitoring
			if (priorExecution.containsKey(request)) {
				final List<FileEvent> eventsToPublish = new ArrayList<FileEvent>();

				final Map<File, Long> priorFiles = priorExecution.get(request);
				for (final Entry<File, Long> entry : priorFiles.entrySet()) {
					final File thisFile = entry.getKey();
					final Long lastModified = entry.getValue();
					eventsToPublish.add(new FileEvent(new FileDetails(thisFile, lastModified), FileOperation.MONITORING_FINISH, null));
				}
				publish(eventsToPublish);
			}

			priorExecution.remove(request);

			return requests.remove(request);
		}
	}

	public SortedSet<FileDetails> findMatchingAntPath(final String antPath) {
		Assert.hasText(antPath, "Ant path required");
		final SortedSet<FileDetails> result = new TreeSet<FileDetails>();
		// Now we need to compute the starting directory by reference to the first * in the Ant Path
		int index = antPath.indexOf("*");
		// Conditionals are based on an index of 0 (not -1) to ensure the detected character is not the only character in the string
		Assert.isTrue(index > 0, "'" + antPath + "' is not an Ant Path as it fails to include an * character");
		String newPath = antPath.substring(0, index);
		index = newPath.lastIndexOf(File.separatorChar);
		Assert.isTrue(index > 0, "'" + antPath + "' fails to include any '" + File.separatorChar + "' directory separator");
		newPath = newPath.substring(0, index);
		final File somePath = new File(newPath);
		if (!somePath.exists()) {
			// Path at the start of the Ant expression doesn't exist, so there's no way we'll find anything via a search
			return result;
		}
		Assert.isTrue(somePath.isDirectory(), "Ant path '" + antPath + "' appears under file system path '" + somePath + "' but this is not a directory that can be searched");
		recursiveAntMatch(antPath, somePath, result);
		return result;
	}

	/**
	 * Locates all files under the specified current directory which patch the given Ant Path.
	 *
	 * @param antPath to match (required)
	 * @param currentDirectory an existing directory to search from (required)
	 * @param result to append located files into (required)
	 */
	private void recursiveAntMatch(final String antPath, final File currentDirectory, final SortedSet<FileDetails> result) {
		Assert.notNull(currentDirectory, "Current directory required");
		Assert.isTrue(currentDirectory.exists() && currentDirectory.isDirectory(), "Path '" + currentDirectory + "' does not exist or is not a directory");
		Assert.hasText(antPath, "Ant path required");
		Assert.notNull(result, "Result required");

		final File[] listFiles = currentDirectory.listFiles();
		if (listFiles == null || listFiles.length == 0) {
			return;
		}
		for (final File f : listFiles) {
			try {
				if (FileUtils.matchesAntPath(antPath, f.getCanonicalPath())) {
					result.add(new FileDetails(f, f.lastModified()));
				}
			} catch (final IOException ignored) {}

			if (f.isDirectory()) {
				recursiveAntMatch(antPath, f, result);
			}
		}
	}

	/**
	 * Decides whether we want to store this notification. This only happens if a monitoring request
	 * has indicated it is interested in this request. See ROO-794 for details.
	 *
	 * @param fileCanonicalPath to potentially keep
	 * @return true if the notification is able to be kept
	 */
	private boolean isNotificationUnderKnownMonitoringRequest(final String fileCanonicalPath) {
		synchronized (lock) {
			for (final MonitoringRequest request : requests) {
				if (isWithin(request, fileCanonicalPath)) {
					return true;
				}
			}
		}
		return false;
	}

	private void updateChanges(final String fileCanonicalPath, final boolean remove) {
		for (final String requestingClass : changeMap.keySet()) {
			if (remove) {
				changeMap.get(requestingClass).remove(fileCanonicalPath);
			} else {
				changeMap.get(requestingClass).add(fileCanonicalPath);
			}
		}
		if (remove) {
			allFiles.remove(fileCanonicalPath);
		} else {
			allFiles.add(fileCanonicalPath);
		}
	}

	private void updateAllFiles(final String fileCanonicalPath, final boolean remove) {
		if (remove) {
			allFiles.remove(fileCanonicalPath);
			updateChanges(fileCanonicalPath, remove);
		} else {
			allFiles.add(fileCanonicalPath);
			if (!allFiles.contains(fileCanonicalPath)) {
				updateChanges(fileCanonicalPath, remove);
			}
		}
	}

	public void notifyChanged(final String fileCanonicalPath) {
		synchronized (lock) {
			updateChanges(fileCanonicalPath, false);
			if (isNotificationUnderKnownMonitoringRequest(fileCanonicalPath)) {
				notifyChanged.add(fileCanonicalPath);
			}
		}
	}

	public void notifyCreated(final String fileCanonicalPath) {
		synchronized (lock) {
			updateChanges(fileCanonicalPath, false);
			if (isNotificationUnderKnownMonitoringRequest(fileCanonicalPath)) {
				notifyCreated.add(fileCanonicalPath);
			}
		}
	}

	public void notifyDeleted(final String fileCanonicalPath) {
		synchronized (lock) {
			updateChanges(fileCanonicalPath, true);
			if (isNotificationUnderKnownMonitoringRequest(fileCanonicalPath)) {
				notifyDeleted.add(fileCanonicalPath);
			}
		}
	}
}
