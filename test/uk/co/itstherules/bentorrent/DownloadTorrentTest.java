package uk.co.itstherules.bentorrent;

import org.junit.Test;

import java.net.URISyntaxException;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public final class DownloadTorrentTest {

    @Test public void fileFoundLocally() throws URISyntaxException {
        final TorrentClient unit = new TorrentClient("");
        assertThat(unit.availableLocally("./lib/junit/4.11/junit.jar"), is(true));
    }

    @Test public void fileNotFoundLocally() throws URISyntaxException {
        final TorrentClient unit = new TorrentClient("");
        assertThat(unit.availableLocally("./lib/junit/4.11/im_not_here"), is(false));
    }

    @Test public void fileRegisteredRemotely() throws URISyntaxException {
        String acceptablePackages = "{\"junit\":{\"4.11\":\"junit.jar\"}}";
        final BenTracker linkServer = new BenTracker(acceptablePackages);
        final TorrentClient unit = new TorrentClient(linkServer.url());
        unit.registerResource("./lib/junit/4.11/junit.jar");
        assertThat(unit.availableToDownload("./lib/junit/4.11/junit.jar"), is(true));
    }

}
