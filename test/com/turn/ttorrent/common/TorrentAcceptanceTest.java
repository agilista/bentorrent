package com.turn.ttorrent.common;

import org.junit.Test;

import java.io.File;
import java.net.URI;

import static org.junit.Assert.assertEquals;


public class TorrentAcceptanceTest {

    @Test
    public void canCreateTorrent() throws Exception {
		URI announceURI = new URI("http://localhost:6969/announce");
		String createdBy = "Test";
		Torrent t = Torrent.create(new File("test_resources/files/file1.jar"), announceURI, createdBy);
		assertEquals(createdBy, t.getCreatedBy());
		assertEquals(announceURI, t.getAnnounceList().get(0).get(0));
	}

    @Test
	public void canLoadTorrentMadeByUTorrent() throws Exception {
		Torrent t = Torrent.load(new File("test_resources/torrents/file1.jar.torrent"));
		assertEquals(new URI("http://localhost:6969/announce"), t.getAnnounceList().get(0).get(0));
		assertEquals("B92D38046C76D73948E14C42DF992CAF25489D08", t.getHexInfoHash());
		assertEquals("uTorrent/3130", t.getCreatedBy());
	}
}
