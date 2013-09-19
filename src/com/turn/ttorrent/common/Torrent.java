/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import uk.co.itstherules.external.SlfLogger;
import uk.co.itstherules.external.SlfLoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/**
 * A torrent file tracked by the controller's BitTorrent tracker.
 * <p/>
 * <p>
 * This class represents an active torrent on the tracker. The torrent
 * information is kept in-memory, and is created from the byte blob one would
 * usually find in a <tt>.torrent</tt> file.
 * </p>
 * <p/>
 * <p>
 * Each torrent also keeps a repository of the peers seeding and leeching this
 * torrent from the tracker.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Metainfo_File_Structure">Torrent meta-info file structure specification</a>
 */
public class Torrent {

    private static final SlfLogger logger = SlfLoggerFactory.getLogger(Torrent.class);

    /**
     * Torrent file piece length (in bytes), we use 512 kB.
     */
    private static final int PIECE_LENGTH = 512 * 1024;

    public static final int PIECE_HASH_SIZE = 20;

    /**
     * The query parameters encoding when parsing byte strings.
     */
    public static final String BYTE_ENCODING = "ISO-8859-1";

    /**
     * @author dgiffin
     * @author mpetazzoni
     */
    public static class TorrentFile {

        public final File file;
        public final long size;

        public TorrentFile(File file, long size) {
            this.file = file;
            this.size = size;
        }
    }

    ;


    protected final byte[] encoded;
    protected final byte[] encodedInfo;
    protected final Map<String, BEValue> decoded;
    protected final Map<String, BEValue> decodedInfo;

    private final byte[] infoHash;
    private final String hexInfoHash;

    private final List<List<URI>> trackers;
    private final Set<URI> allTrackers;
    private final Date creationDate;
    private final String comment;
    private final String createdBy;
    private final String name;
    private final long size;
    protected final List<TorrentFile> files;

    private final boolean seeder;

    /**
     * Create a new torrent from meta-info binary data.
     * <p/>
     * Parses the meta-info data (which should be B-encoded as described in the
     * BitTorrent specification) and create a Torrent object from it.
     *
     * @param torrent The meta-info byte data.
     * @param seeder  Whether we'll be seeding for this torrent or not.
     * @throws IOException When the info dictionary can't be read or
     *                     encoded and hashed back to create the torrent's SHA-1 hash.
     */
    public Torrent(byte[] torrent, boolean seeder) throws IOException {
        this.encoded = torrent;
        this.seeder = seeder;

        decoded = BDecoder.bdecode(new ByteArrayInputStream(encoded)).getMap();

        decodedInfo = decoded.get("info").getMap();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(this.decodedInfo, baos);
        encodedInfo = baos.toByteArray();
        infoHash = Torrent.hash(encodedInfo);
        hexInfoHash = Torrent.byteArrayToHexString(infoHash);

        /**
         * Parses the announce information from the decoded meta-info
         * structure.
         *
         * <p>
         * If the torrent doesn't define an announce-list, use the mandatory
         * announce field value as the single tracker in a single announce
         * tier.  Otherwise, the announce-list must be parsed and the trackers
         * from each tier extracted.
         * </p>
         *
         * @see <a href="http://bittorrent.org/beps/bep_0012.html">BitTorrent BEP#0012 "Multitracker Metadata Extension"</a>
         */
        try {
            trackers = new ArrayList<List<URI>>();
            allTrackers = new HashSet<URI>();

            if (decoded.containsKey("announce-list")) {
                List<BEValue> tiers = decoded.get("announce-list").getList();
                for (BEValue tv : tiers) {
                    List<BEValue> trackers = tv.getList();
                    if (trackers.isEmpty()) {
                        continue;
                    }

                    List<URI> tier = new ArrayList<URI>();
                    for (BEValue tracker : trackers) {
                        URI uri = new URI(tracker.getString());

                        // Make sure we're not adding duplicate trackers.
                        if (!allTrackers.contains(uri)) {
                            tier.add(uri);
                            allTrackers.add(uri);
                        }
                    }

                    // Only add the tier if it's not empty.
                    if (!tier.isEmpty()) {
                        this.trackers.add(tier);
                    }
                }
            } else if (decoded.containsKey("announce")) {
                URI tracker = new URI(decoded.get("announce").getString());
                allTrackers.add(tracker);

                // Build a single-tier announce list.
                List<URI> tier = new ArrayList<URI>();
                tier.add(tracker);
                this.trackers.add(tier);
            }
        } catch (URISyntaxException use) {
            throw new IOException(use);
        }

        creationDate = decoded.containsKey("creation date")
                ? new Date(decoded.get("creation date").getLong() * 1000)
                : null;
        comment = decoded.containsKey("comment")
                ? decoded.get("comment").getString()
                : null;
        createdBy = decoded.containsKey("created by")
                ? decoded.get("created by").getString()
                : null;
        name = decodedInfo.get("name").getString();

        files = new LinkedList<TorrentFile>();

        // Parse multi-file torrent file information structure.
        if (decodedInfo.containsKey("files")) {
            for (BEValue file : decodedInfo.get("files").getList()) {
                Map<String, BEValue> fileInfo = file.getMap();
                StringBuilder path = new StringBuilder();
                for (BEValue pathElement : fileInfo.get("path").getList()) {
                    path.append(File.separator)
                            .append(pathElement.getString());
                }
                files.add(new TorrentFile(
                        new File(name, path.toString()),
                        fileInfo.get("length").getLong()));
            }
        } else {
            // For single-file torrents, the name of the torrent is
            // directly the name of the file.
            files.add(new TorrentFile(
                    new File(name),
                    decodedInfo.get("length").getLong()));
        }

        // Calculate the total size of this torrent from its files' sizes.
        long size = 0;
        for (TorrentFile file : files) {
            size += file.size;
        }
        this.size = size;

        logger.info("{0}-file torrent information:",
                isMultifile() ? "Multi" : "Single");
        logger.info("  Torrent name: {0}", name);
        logger.info("  Announced at:" + (this.trackers.size() == 0 ? " Seems to be trackerless" : ""));
        for (int i = 0; i < this.trackers.size(); i++) {
            List<URI> tier = this.trackers.get(i);
            for (int j = 0; j < tier.size(); j++) {
                logger.info("    {0}{1}",
                        (j == 0 ? String.format("%2d. ", i + 1) : "    "),
                        tier.get(j));
            }
        }

        if (creationDate != null) {
            logger.info("  Created on..: {0}", creationDate);
        }
        if (comment != null) {
            logger.info("  Comment.....: {0}", comment);
        }
        if (createdBy != null) {
            logger.info("  Created by..: {0}", createdBy);
        }

        if (isMultifile()) {
            logger.info("  Found {0} file(s) in multi-file torrent structure.", files.size());
            int i = 0;
            for (TorrentFile file : files) {
                logger.debug("    {0}. {1} ({2} byte(s))", String.format("%2d", ++i), file.file.getPath(), String.format("%,d", file.size));
            }
        }

        logger.info("  Pieces......: {0} piece(s) ({1} byte(s)/piece)", (this.size / decodedInfo.get("piece length").getInt()) + 1,
                decodedInfo.get("piece length").getInt());
        logger.info("  Total size..: {0} byte(s)", String.format("%,d", this.size));
    }

    /**
     * Get this torrent's name.
     * <p/>
     * <p>
     * For a single-file torrent, this is usually the name of the file. For a
     * multi-file torrent, this is usually the name of a top-level directory
     * containing those files.
     * </p>
     */
    public String getName() {
        return name;
    }

    /**
     * Get this torrent's comment string.
     */
    public String getComment() {
        return comment;
    }

    /**
     * Get this torrent's creator (user, software, whatever...).
     */
    public String getCreatedBy() {
        return createdBy;
    }

    /**
     * Get the total size of this torrent.
     */
    public long getSize() {
        return size;
    }

    /**
     * Get the file names from this torrent.
     *
     * @return The list of relative filenames of all the files described in
     *         this torrent.
     */
    public List<String> getFilenames() {
        List<String> filenames = new LinkedList<String>();
        for (TorrentFile file : files) {
            filenames.add(file.file.getPath());
        }
        return filenames;
    }

    /**
     * Tells whether this torrent is multi-file or not.
     */
    public boolean isMultifile() {
        return files.size() > 1;
    }

    /**
     * Return the hash of the B-encoded meta-info structure of this torrent.
     */
    public byte[] getInfoHash() {
        return infoHash;
    }

    /**
     * Get this torrent's info hash (as an hexadecimal-coded string).
     */
    public String getHexInfoHash() {
        return hexInfoHash;
    }

    /**
     * Return a human-readable representation of this torrent object.
     * <p/>
     * <p>
     * The torrent's name is used.
     * </p>
     */
    public String toString() {
        return getName();
    }

    /**
     * Return the B-encoded meta-info of this torrent.
     */
    public byte[] getEncoded() {
        return encoded;
    }

    /**
     * Return the trackers for this torrent.
     */
    public List<List<URI>> getAnnounceList() {
        return trackers;
    }

    /**
     * Returns the number of trackers for this torrent.
     */
    public int getTrackerCount() {
        return allTrackers.size();
    }

    /**
     * Tells whether we were an initial seeder for this torrent.
     */
    public boolean isSeeder() {
        return seeder;
    }

    /**
     * Save this torrent meta-info structure into a .torrent file.
     *
     * @param output The stream to write to.
     * @throws IOException If an I/O error occurs while writing the file.
     */
    public void save(OutputStream output) throws IOException {
        output.write(getEncoded());
    }

    public static byte[] hash(byte[] data) {
        MessageDigest md = null;
        md = sha1MessageDigest();
        md.update(data);
        return md.digest();
    }

    private static MessageDigest sha1MessageDigest() {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        return md;
    }

    /**
     * Convert a byte string to a string containing an hexadecimal
     * representation of the original data.
     *
     * @param bytes The byte array to convert.
     */
    public static String byteArrayToHexString(byte[] bytes) {
        BigInteger bi = new BigInteger(1, bytes);
        return String.format("%0" + (bytes.length << 1) + "X", bi);
    }

    /**
     * Return an hexadecimal representation of the bytes contained in the
     * given string, following the default, expected byte encoding.
     *
     * @param input The input string.
     */
    public static String toHexString(String input) {
        try {
            byte[] bytes = input.getBytes(Torrent.BYTE_ENCODING);
            return Torrent.byteArrayToHexString(bytes);
        } catch (UnsupportedEncodingException uee) {
            return null;
        }
    }

    /**
     * Determine how many threads to use for the piece hashing.
     * <p/>
     * <p>
     * If the environment variable TTORRENT_HASHING_THREADS is set to an
     * integer value greater than 0, its value will be used. Otherwise, it
     * defaults to the number of processors detected by the Java Runtime.
     * </p>
     *
     * @return How many threads to use for concurrent piece hashing.
     */
    protected static int getHashingThreadsCount() {
        String threads = System.getenv("TTORRENT_HASHING_THREADS");

        if (threads != null) {
            try {
                int count = Integer.parseInt(threads);
                if (count > 0) {
                    return count;
                }
            } catch (NumberFormatException nfe) {
                // Pass
            }
        }

        return Runtime.getRuntime().availableProcessors();
    }

    /** Torrent loading ---------------------------------------------------- */

    /**
     * Load a torrent from the given torrent file.
     * <p/>
     * <p>
     * This method assumes we are not a seeder and that local data needs to be
     * validated.
     * </p>
     *
     * @param torrent The abstract {@link File} object representing the
     *                <tt>.torrent</tt> file to load.
     * @throws IOException When the torrent file cannot be read.
     */
    public static Torrent load(File torrent)
            throws IOException {
        return Torrent.load(torrent, false);
    }

    /**
     * Load a torrent from the given torrent file.
     *
     * @param torrent The abstract {@link File} object representing the
     *                <tt>.torrent</tt> file to load.
     * @param seeder  Whether we are a seeder for this torrent or not (disables
     *                local data validation).
     * @throws IOException              When the torrent file cannot be read.
     * @throws NoSuchAlgorithmException
     */
    public static Torrent load(File torrent, boolean seeder) throws IOException {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(torrent);
            byte[] data = new byte[(int) torrent.length()];
            fis.read(data);
            return new Torrent(data, seeder);
        } finally {
            if (fis != null) {
                fis.close();
            }
        }
    }

    /** Torrent creation --------------------------------------------------- */

    /**
     * Create a {@link Torrent} object for a file.
     * <p/>
     * <p>
     * Hash the given file to create the {@link Torrent} object representing
     * the Torrent metainfo about this file, needed for announcing and/or
     * sharing said file.
     * </p>
     *
     * @param source    The file to use in the torrent.
     * @param announce  The announce URI that will be used for this torrent.
     * @param createdBy The creator's name, or any string identifying the
     *                  torrent's creator.
     */
    public static Torrent create(File source, URI announce, String createdBy)
            throws InterruptedException, IOException {
        return Torrent.create(source, null, announce, null, createdBy);
    }

    /**
     * Create a {@link Torrent} object for a set of files.
     * <p/>
     * <p>
     * Hash the given files to create the multi-file {@link Torrent} object
     * representing the Torrent meta-info about them, needed for announcing
     * and/or sharing these files. Since we created the torrent, we're
     * considering we'll be a full initial seeder for it.
     * </p>
     *
     * @param parent    The parent directory or location of the torrent files,
     *                  also used as the torrent's name.
     * @param files     The files to add into this torrent.
     * @param announce  The announce URI that will be used for this torrent.
     * @param createdBy The creator's name, or any string identifying the
     *                  torrent's creator.
     */
    public static Torrent create(File parent, List<File> files, URI announce, String createdBy)
            throws InterruptedException, IOException {
        return Torrent.create(parent, files, announce, null, createdBy);
    }

    /**
     * Create a {@link Torrent} object for a file.
     * <p/>
     * <p>
     * Hash the given file to create the {@link Torrent} object representing
     * the Torrent metainfo about this file, needed for announcing and/or
     * sharing said file.
     * </p>
     *
     * @param source       The file to use in the torrent.
     * @param announceList The announce URIs organized as tiers that will
     *                     be used for this torrent
     * @param createdBy    The creator's name, or any string identifying the
     *                     torrent's creator.
     */
    public static Torrent create(File source, List<List<URI>> announceList,
                                 String createdBy) throws InterruptedException, IOException {
        return Torrent.create(source, null, null, announceList, createdBy);
    }

    /**
     * Create a {@link Torrent} object for a set of files.
     * <p/>
     * <p>
     * Hash the given files to create the multi-file {@link Torrent} object
     * representing the Torrent meta-info about them, needed for announcing
     * and/or sharing these files. Since we created the torrent, we're
     * considering we'll be a full initial seeder for it.
     * </p>
     *
     * @param source       The parent directory or location of the torrent files,
     *                     also used as the torrent's name.
     * @param files        The files to add into this torrent.
     * @param announceList The announce URIs organized as tiers that will
     *                     be used for this torrent
     * @param createdBy    The creator's name, or any string identifying the
     *                     torrent's creator.
     */
    public static Torrent create(File source, List<File> files, List<List<URI>> announceList, String createdBy)
            throws InterruptedException, IOException {
        return Torrent.create(source, files, null, announceList, createdBy);
    }

    /**
     * Helper method to create a {@link Torrent} object for a set of files.
     * <p/>
     * <p>
     * Hash the given files to create the multi-file {@link Torrent} object
     * representing the Torrent meta-info about them, needed for announcing
     * and/or sharing these files. Since we created the torrent, we're
     * considering we'll be a full initial seeder for it.
     * </p>
     *
     * @param parent       The parent directory or location of the torrent files,
     *                     also used as the torrent's name.
     * @param files        The files to add into this torrent.
     * @param announce     The announce URI that will be used for this torrent.
     * @param announceList The announce URIs organized as tiers that will
     *                     be used for this torrent
     * @param createdBy    The creator's name, or any string identifying the
     *                     torrent's creator.
     */
    private static Torrent create(File parent, List<File> files, URI announce, List<List<URI>> announceList, String createdBy)
            throws InterruptedException, IOException {
        if (files == null || files.isEmpty()) {
            logger.info("Creating single-file torrent for {0}...", parent.getName());
        } else {
            logger.info("Creating {0}-file torrent {1}...", files.size(), parent.getName());
        }

        Map<String, BEValue> torrent = new HashMap<String, BEValue>();

        if (announce != null) {
            torrent.put("announce", new BEValue(announce.toString()));
        }
        if (announceList != null) {
            List<BEValue> tiers = new LinkedList<BEValue>();
            for (List<URI> trackers : announceList) {
                List<BEValue> tierInfo = new LinkedList<BEValue>();
                for (URI trackerURI : trackers) {
                    tierInfo.add(new BEValue(trackerURI.toString()));
                }
                tiers.add(new BEValue(tierInfo));
            }
            torrent.put("announce-list", new BEValue(tiers));
        }

        torrent.put("creation date", new BEValue(new Date().getTime() / 1000));
        torrent.put("created by", new BEValue(createdBy));

        Map<String, BEValue> info = new TreeMap<String, BEValue>();
        info.put("name", new BEValue(parent.getName()));
        info.put("piece length", new BEValue(Torrent.PIECE_LENGTH));

        if (files == null || files.isEmpty()) {
            info.put("length", new BEValue(parent.length()));
            info.put("pieces", new BEValue(Torrent.hashFile(parent),
                    Torrent.BYTE_ENCODING));
        } else {
            List<BEValue> fileInfo = new LinkedList<BEValue>();
            for (File file : files) {
                Map<String, BEValue> fileMap = new HashMap<String, BEValue>();
                fileMap.put("length", new BEValue(file.length()));

                LinkedList<BEValue> filePath = new LinkedList<BEValue>();
                while (file != null) {
                    if (file.equals(parent)) {
                        break;
                    }

                    filePath.addFirst(new BEValue(file.getName()));
                    file = file.getParentFile();
                }

                fileMap.put("path", new BEValue(filePath));
                fileInfo.add(new BEValue(fileMap));
            }
            info.put("files", new BEValue(fileInfo));
            info.put("pieces", new BEValue(Torrent.hashFiles(files),
                    Torrent.BYTE_ENCODING));
        }
        torrent.put("info", new BEValue(info));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(new BEValue(torrent), baos);
        return new Torrent(baos.toByteArray(), true);
    }

    /**
     * A {@link Callable} to hash a data chunk.
     *
     * @author mpetazzoni
     */
    private static class CallableChunkHasher implements Callable<String> {

        private final MessageDigest md;
        private final ByteBuffer data;

        CallableChunkHasher(ByteBuffer buffer) {
            md = sha1MessageDigest();

            data = ByteBuffer.allocate(buffer.remaining());
            buffer.mark();
            data.put(buffer);
            data.clear();
            buffer.reset();
        }

        @Override
        public String call() throws UnsupportedEncodingException {
            md.reset();
            md.update(data.array());
            return new String(md.digest(), Torrent.BYTE_ENCODING);
        }
    }

    /**
     * Return the concatenation of the SHA-1 hashes of a file's pieces.
     * <p/>
     * <p>
     * Hashes the given file piece by piece using the default Torrent piece
     * length (see {@link #PIECE_LENGTH}) and returns the concatenation of
     * these hashes, as a string.
     * </p>
     * <p/>
     * <p>
     * This is used for creating Torrent meta-info structures from a file.
     * </p>
     *
     * @param file The file to hash.
     */
    private static String hashFile(File file) throws InterruptedException, IOException {
        return Torrent.hashFiles(Arrays.asList(new File[]{file}));
    }

    private static String hashFiles(List<File> files) throws InterruptedException, IOException {
        int threads = getHashingThreadsCount();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        ByteBuffer buffer = ByteBuffer.allocate(Torrent.PIECE_LENGTH);
        List<Future<String>> results = new LinkedList<Future<String>>();
        StringBuilder hashes = new StringBuilder();

        long length = 0L;
        int pieces = 0;

        long start = System.nanoTime();
        for (File file : files) {
            logger.info("Hashing data from {0} with {1} threads ({2} pieces)...", file.getName(), threads,
                    (int) (Math.ceil((double) file.length() / Torrent.PIECE_LENGTH)));

            length += file.length();

            FileInputStream fis = new FileInputStream(file);
            FileChannel channel = fis.getChannel();
            int step = 10;

            try {
                while (channel.read(buffer) > 0) {
                    if (buffer.remaining() == 0) {
                        buffer.clear();
                        results.add(executor.submit(new CallableChunkHasher(buffer)));
                    }

                    if (results.size() >= threads) {
                        pieces += accumulateHashes(hashes, results);
                    }

                    if (channel.position() / (double) channel.size() * 100f > step) {
                        logger.info("  ... {0}% complete", step);
                        step += 10;
                    }
                }
            } finally {
                channel.close();
                fis.close();
            }
        }

        // Hash the last bit, if any
        if (buffer.position() > 0) {
            buffer.limit(buffer.position());
            buffer.position(0);
            results.add(executor.submit(new CallableChunkHasher(buffer)));
        }

        pieces += accumulateHashes(hashes, results);

        // Request orderly executor shutdown and wait for hashing tasks to
        // complete.
        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(10);
        }
        long elapsed = System.nanoTime() - start;
        int expectedPieces = (int) (Math.ceil((double) length / Torrent.PIECE_LENGTH));
        logger.info("Hashed {0} file(s) ({1} bytes) in {2} pieces ({3} expected) in {4}ms.", files.size(), length,
                pieces, expectedPieces, String.format("%.1f", elapsed / 1e6));
        return hashes.toString();
    }

    /**
     * Accumulate the piece hashes into a given {@link StringBuilder}.
     *
     * @param hashes  The {@link StringBuilder} to append hashes to.
     * @param results The list of {@link Future}s that will yield the piece
     *                hashes.
     */
    private static int accumulateHashes(StringBuilder hashes,
                                        List<Future<String>> results) throws InterruptedException, IOException {
        try {
            int pieces = results.size();
            for (Future<String> chunk : results) {
                hashes.append(chunk.get());
            }
            results.clear();
            return pieces;
        } catch (ExecutionException ee) {
            throw new IOException("Error while hashing the torrent data!", ee);
        }
    }
}
