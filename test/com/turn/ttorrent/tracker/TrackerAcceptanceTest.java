package com.turn.ttorrent.tracker;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.testutil.TempFiles;
import com.turn.ttorrent.testutil.WaitFor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.co.itstherules.external.ApacheFileUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static org.junit.Assert.*;

public class TrackerAcceptanceTest {

    private static final int TRACKER_PORT = 6969;
    private static final String TEST_FILES_FOLDER = "test_resources/files";

    private TempFiles tempFiles;
    private Torrent testTorrent;
    private Tracker tracker;
    private File testFile;

    @Before
    public void setUp() throws Exception {
        tempFiles = new TempFiles();
        startTracker();
        testFile = new File(TEST_FILES_FOLDER + "/file1.jar");
        String creator = String.format("%s (ttorrent)", "A Test");
        testTorrent = Torrent.create(testFile, tracker.getAnnounceUrl().toURI(), creator);
    }

    @After
    public void tearDown() throws Exception {
        stopTracker();
        tempFiles.cleanup();
    }


    @Test
    public void canShareAndDownload() throws Exception {
        final TrackedTorrent trackedTorrent = tracker.announce(loadTorrent());
        assertEquals(0, trackedTorrent.getPeers().size());
        Client seeder = createClient(completeTorrent());
        assertEquals(trackedTorrent.getHexInfoHash(), seeder.getTorrent().getHexInfoHash());
        final File downloadDir = tempFiles.createTempDir();
        Client leech = createClient(incompleteTorrent(downloadDir));
        try {
            seeder.share();
            leech.download();
            waitForFileInDir(downloadDir, testFile.getName());
            assertFilesEqual(testFile, new File(downloadDir, testFile.getName()));
        } finally {
            leech.stop(true);
            seeder.stop(true);
        }
    }


    @Test
    public void trackerAcceptsTorrentFromLeech() throws Exception {
        final File downloadDir = tempFiles.createTempDir();
        final SharedTorrent torrent = incompleteTorrent(downloadDir);
        tracker.announce(new TrackedTorrent(torrent));
        Client leech = createClient(torrent);

        try {
            leech.download();

            waitForPeers(1);

            Collection<TrackedTorrent> trackedTorrents = tracker.getTrackedTorrents();
            assertEquals(1, trackedTorrents.size());

            TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
            Map<String, TrackedPeer> peers = trackedTorrent.getPeers();
            assertEquals(1, peers.size());
            assertFalse(peers.values().iterator().next().isCompleted()); // leech
            assertEquals(0, trackedTorrent.seeders());
            assertEquals(1, trackedTorrent.leechers());
        } finally {
            leech.stop(true);
        }
    }

    @Test
    public void trackerAcceptsTorrentFromSeeder() throws Exception {
        final SharedTorrent torrent = completeTorrent();
        tracker.announce(new TrackedTorrent(torrent));
        Client seeder = createClient(torrent);
        try {
            seeder.share();
            waitForSeeder(seeder.getTorrent().getInfoHash());
            Collection<TrackedTorrent> trackedTorrents = tracker.getTrackedTorrents();
            assertEquals(1, trackedTorrents.size());
            TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
            Map<String, TrackedPeer> peers = trackedTorrent.getPeers();
            assertEquals(1, peers.size());
            assertTrue(peers.values().iterator().next().isCompleted()); // seed
            assertEquals(1, trackedTorrent.seeders());
            assertEquals(0, trackedTorrent.leechers());
        } finally {
            seeder.stop(true);
        }
    }

    @Test
    public void trackerAcceptsTorrentFromSeederPlusLeech() throws Exception {
        assertEquals(0, tracker.getTrackedTorrents().size());

        final SharedTorrent completeTorrent = completeTorrent();
        tracker.announce(new TrackedTorrent(completeTorrent));
        Client seeder = createClient(completeTorrent);

        final File downloadDir = tempFiles.createTempDir();
        final SharedTorrent incompleteTorrent = incompleteTorrent(downloadDir);
        Client leech = createClient(incompleteTorrent);

        try {
            seeder.share();
            leech.download();

            waitForFileInDir(downloadDir, testFile.getName());
        } finally {
            seeder.stop(true);
            leech.stop(true);
        }
    }

    @Test
    public void canDownloadALargeFile() throws Exception {

        File tempFile = tempFiles.createTempFile(201 * 1024 * 1024);

        Torrent torrent = Torrent.create(tempFile, tracker.getAnnounceUrl().toURI(), "Test");
        File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
        ApacheFileUtils.writeByteArrayToFile(torrentFile, torrent.getEncoded());
        tracker.announce(new TrackedTorrent(torrent));

        Client seeder = createClient(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile()));

        final File downloadDir = tempFiles.createTempDir();
        Client leech = createClient(SharedTorrent.fromFile(torrentFile, downloadDir));

        try {
            seeder.share();
            leech.download();

            waitForFileInDir(downloadDir, tempFile.getName());
            assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
        } finally {
            seeder.stop(true);
            leech.stop(true);
        }
    }

    @Test
    public void canAnnounceTorrent() throws Exception {
        assertEquals(0, tracker.getTrackedTorrents().size());

        tracker.announce(loadTorrent());

        assertEquals(1, tracker.getTrackedTorrents().size());
    }

    private void waitForSeeder(final byte[] torrentHash) {
        new WaitFor() {
            @Override
            protected boolean condition() {
                for (TrackedTorrent tt : TrackerAcceptanceTest.this.tracker.getTrackedTorrents()) {
                    if (tt.seeders() == 1 && tt.getHexInfoHash().equals(Torrent.byteArrayToHexString(torrentHash)))
                        return true;
                }

                return false;
            }
        };
    }

    private void waitForPeers(final int numPeers) {
        new WaitFor() {
            @Override
            protected boolean condition() {
                for (TrackedTorrent tt : TrackerAcceptanceTest.this.tracker.getTrackedTorrents()) {
                    if (tt.getPeers().size() == numPeers) return true;
                }

                return false;
            }
        };
    }

    private void waitForFileInDir(final File downloadDir, final String fileName) {
        new WaitFor(120 * 1000) {
            @Override
            protected boolean condition() {
                return new File(downloadDir, fileName).isFile();
            }
        };

        assertTrue(new File(downloadDir, fileName).isFile());
    }

    private TrackedTorrent loadTorrent() throws IOException {
        return new TrackedTorrent(testTorrent);
    }

    private void startTracker() throws IOException {
        tracker = new Tracker(new InetSocketAddress(TRACKER_PORT));
        tracker.start();
    }

    private Client createClient(SharedTorrent torrent) throws IOException, InterruptedException {
        return new Client(InetAddress.getLocalHost(), torrent);
    }

    private SharedTorrent completeTorrent() throws IOException {
        File parentFiles = new File(TEST_FILES_FOLDER);
        return new SharedTorrent(testTorrent, parentFiles);
    }

    private SharedTorrent incompleteTorrent(File destDir) throws IOException {
        return new SharedTorrent(testTorrent, destDir);
    }

    private void stopTracker() {
        tracker.stop();
    }

    private void assertFilesEqual(File f1, File f2) throws IOException {
        assertEquals("Files size differs", f1.length(), f2.length());
        Checksum c1 = ApacheFileUtils.checksum(f1, new CRC32());
        Checksum c2 = ApacheFileUtils.checksum(f2, new CRC32());
        assertEquals(c1.getValue(), c2.getValue());
    }
}