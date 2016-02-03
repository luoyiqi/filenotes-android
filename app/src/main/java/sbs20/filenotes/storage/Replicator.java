package sbs20.filenotes.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import sbs20.filenotes.DateTime;
import sbs20.filenotes.ServiceManager;
import sbs20.filenotes.model.Logger;
import sbs20.filenotes.model.Settings;

public class Replicator {

    public enum EventType {
        LocalUpdate,
        LocalDelete,
        RemoteUpdate,
        RemoteDelete
    }

    public class Event {
        private EventType type;
        private String filePath;

        public Event(EventType type, String filePath) {
            this.type = type;
            this.filePath = filePath;
        }

        private String toString(EventType type) {
            switch (type) {
                case LocalDelete:
                    return "Local delete";
                case LocalUpdate:
                    return "Local update";
                case RemoteDelete:
                    return "Remote delete";
                case RemoteUpdate:
                    return "Remote update";
                default:
                    return "Unknown";
            }
        }

        public String message() {
            return toString(this.type) + ": " + this.filePath;
        }
    }

    public interface IReplicatorObserver {
        void update(Replicator source, Event event);
    }

    private static boolean isRunning = false;
    private List<File> localFiles;
    private List<File> remoteFiles;
    private Logger logger;
    private CloudService cloudService;

    private List<IReplicatorObserver> observers;
    private List<File> downloads;
    private List<File> uploads;
    private List<File> localDeletes;
    private List<File> remoteDeletes;

    public Replicator() {
        this.localFiles = new ArrayList<>();
        this.remoteFiles = new ArrayList<>();
        this.cloudService = ServiceManager.getInstance().getCloudService();
        this.observers = new ArrayList<>();
        this.downloads = new ArrayList<>();
        this.uploads = new ArrayList<>();
        this.localDeletes = new ArrayList<>();
        this.remoteDeletes = new ArrayList<>();
    }

    private void add(java.io.File localfile) {
        this.localFiles.add(new File(localfile));
    }

    private void add(java.io.File[] localFiles) {
        for (java.io.File f : localFiles) {
            this.add(f);
        }
    }

    private void loadLocalFiles() {
        this.add(new FileSystemService().readAllFilesFromStorage());
    }

    private void add(File file) {
        this.remoteFiles.add(file);
    }

    private void add(List<File> files) {
        Settings settings = ServiceManager.getInstance().getSettings();
        for (File file : files) {
            this.add(file);
        }
    }

    private void loadRemoteFiles() throws IOException {
        this.add(cloudService.files());
    }

    private File findLocal(File remoteFile) {
        for (File localFile : this.localFiles) {
            if (localFile.getName().equals(remoteFile.getName())) {
                return localFile;
            }
        }
        return null;
    }

    private File findRemote(File localFile) {
        for (File remoteFile : this.remoteFiles) {
            if (remoteFile.getName().equals(localFile.getName())) {
                return remoteFile;
            }
        }
        return null;
    }

    private void download(File remoteFile) throws Exception {
        logger.info(this, "download(" + remoteFile.getName() + ")");
        cloudService.download(remoteFile);
        downloads.add(remoteFile);
        this.raiseEvent(new Event(EventType.LocalUpdate, remoteFile.getPath()));
    }

    private void upload(File localFile) throws Exception {
        logger.info(this, "upload(" + localFile.getName() + ")");
        cloudService.upload(localFile);
        uploads.add(localFile);
        this.raiseEvent(new Event(EventType.RemoteUpdate, localFile.getPath()));
    }

    private void deleteLocal(File localFile) {
        logger.info(this, "deleteLocal(" + localFile.getName() + ")");
        new FileSystemService().delete(localFile.getName());
        localDeletes.add(localFile);
        this.raiseEvent(new Event(EventType.LocalDelete, localFile.getPath()));
    }

    private void deleteRemote(File remoteFile) throws Exception {
        logger.info(this, "deleteRemote(" + remoteFile.getName() + ")");
        cloudService.delete(remoteFile);
        remoteDeletes.add(remoteFile);
        this.raiseEvent(new Event(EventType.RemoteDelete, remoteFile.getPath()));
    }

    private void resolveConflict(File localFile, File remoteFile) throws Exception {
        logger.info(this, "resolveConflict(" + remoteFile.getName() + ")");
        // We already have the local file. So download the server one but call it <file>.server-conflict
        cloudService.download(remoteFile, localFile.getName() + ".conflict");
    }

    private void firstSync() throws Exception {
        logger.info(this, "firstSync:Start");

        // Start with local stuff
        for (File localFile : this.localFiles) {

            // Get remote
            File remoteFile = this.findRemote(localFile);

            if (remoteFile == null) {

                logger.debug(this, "firstSync:" + localFile.getName() + ":remote is null");
                this.upload(localFile);

            } else if (localFile.getLastModified().compareTo(remoteFile.getLastModified()) > 0) {

                logger.debug(this, "firstSync:" + localFile.getName() + ":local is newer");
                this.upload(localFile);

            } else if (localFile.getLastModified().compareTo(remoteFile.getLastModified()) == 0) {

                logger.debug(this, "firstSync:" + localFile.getName() + ":local and remote same age");
                if (localFile.getSize() == remoteFile.getSize()) {
                    logger.debug(this, "firstSync:" + localFile.getName() + ":local and remote same size");
                } else {
                    logger.debug(this, "firstSync:" + localFile.getName() + ":local and remote different sizes");
                    this.resolveConflict(localFile, remoteFile);
                }

            } else if (localFile.getLastModified().compareTo(remoteFile.getLastModified()) < 0) {

                logger.debug(this, "firstSync:" + localFile.getName() + ":remote is newer");
                this.download(remoteFile);

            }
        }

        // Now remote
        for (File remoteFile : this.remoteFiles) {

            // Get local
            File localFile = this.findLocal(remoteFile);

            if (localFile == null) {
                // We only need to deal with the ones where there isn't a local file
                logger.debug(this, "firstSync:" + remoteFile.getName() + ":local is null");
                this.download(remoteFile);
            }
        }
    }

    private void sync(Date lastSync) throws Exception {

        // Start with local stuff
        for (File localFile : this.localFiles) {

            // Get remote
            File remoteFile = this.findRemote(localFile);

            if (localFile.getLastModified().compareTo(lastSync) <= 0) {
                // Local file unchanged
                if (remoteFile == null) {
                    this.deleteLocal(localFile);
                } else if (remoteFile.getLastModified().compareTo(lastSync) > 0) {
                    this.download(remoteFile);
                } else {
                    logger.debug(this, "sync:" + localFile.getName() + ":skip");
                }

            } else {
                // Local file changed
                if (remoteFile == null) {
                    this.upload(localFile);
                } else if (remoteFile.getLastModified().compareTo(lastSync) <= 0) {
                    this.upload(localFile);
                } else if (remoteFile.getLastModified().compareTo(lastSync) > 0) {
                    this.resolveConflict(localFile, remoteFile);
                }
            }
        }

        // Now remote
        for (File remoteFile : this.remoteFiles) {

            // Get local
            File localFile = this.findLocal(remoteFile);

            // We only care if the local file is null (we dealt with the others above)
            if (localFile == null) {
                // If the remote file hasn't been touched...
                if (remoteFile.getLastModified().compareTo(lastSync) <= 0) {
                    this.deleteRemote(remoteFile);
                } else {
                    this.download(remoteFile);
                }
            }
        }
    }

    public void invoke() {

        if (!isRunning) {
            isRunning = true;

            logger.info(this, "invoke()");

            try {
                // Load local files
                this.loadLocalFiles();

                // Load remote files
                this.loadRemoteFiles();

                // Look at everything that's happened since the last sync
                Date lastSync = ServiceManager.getInstance().getSettings().getLastSync();

                if (lastSync.equals(DateTime.min())) {
                    // This is the first sync. We lack data. One would hope that either one or both
                    // nodes of this is completely empty. If both are populated then we don't delete
                    // anything and we go last localChange wins
                    logger.debug(this, "First sync");
                    this.firstSync();

                } else {

                    logger.debug(this, "sync (previous success:" + DateTime.to8601String(lastSync) + ")");
                    this.sync(lastSync);
                }

                // Files just downloaded will have new dates - and there is no workaround to this. So
                // we need to record the date time as of now to avoid unnecessary uploading of files
                Date now = DateTime.now();
                ServiceManager.getInstance().getSettings().setLastSync(now);

                // Also clear the need for further replications
                ServiceManager.getInstance().getNotesManager().setReplicationRequired(false);

            } catch (Exception ex) {
                logger.debug(this, "invoke():error:loadRemoteFiles:" + ex.toString());
            }

            isRunning = false;
        } else {
            logger.debug(this, "invoke():already running!");
        }
    }

    private void raiseEvent(Event event) {
        for (IReplicatorObserver observer : this.observers) {
            observer.update(this, event);
        }
    }

    public void addObserver(IReplicatorObserver observer) {
        this.observers.add(observer);
    }

    public int getUpdateCount() {
        return downloads.size() + uploads.size() + localDeletes.size() + remoteDeletes.size();
    }
}