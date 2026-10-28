package com.carddemo.migration;

import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The IMS HD unload of DBPAUTP0 ({@code AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat}, DFSURGU0 format): variable
 * records with a 4-byte length prefix; a statistics header, one record per segment in hierarchic order
 * (35-byte unload prefix naming the segment, a 2-byte value of the source database, the segment, one
 * zero byte), and a statistics trailer with the root and child counts. Loading skips a root whose key is
 * not a valid account number (the sample's all-X'40' root, Q-PAUDBUNL-02) and its children; the 2-byte
 * value is kept in {@code legacy_rba} so the export reproduces the file.
 */
public class ImsPendingAuthUnload {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] HEADER = HEX.parseHex("0058000000800050d7c1e4e3e2e4d4f0" + "00".repeat(24)
        + "0000000001010072d7c1e4e3c4e3d3f1" + "00".repeat(24) + "00000000020280ce");
    private static final byte[] ROOT_PREFIX = HEX.parseHex("0000018000230064d7c1e4e3e2e4d4f0" + "00".repeat(19));
    private static final byte[] CHILD_PREFIX = HEX.parseHex("00000280002300c8d7c1e4e3c4e3d3f1" + "00".repeat(19));
    private static final int TRAILER_LENGTH = 88;
    private static final int ROOT_RECORD = 140;
    private static final int CHILD_RECORD = 240;

    private final JdbcTemplate jdbc;

    public ImsPendingAuthUnload(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Result(int roots, int children, int invalidRoots, int skippedChildren) {
    }

    /** The records of a DFSURGU0 file, each with its length prefix. */
    public static List<byte[]> records(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int off = 0;
        while (off < data.length) {
            if (off + 4 > data.length) {
                throw new IllegalArgumentException("truncated record prefix at byte " + off);
            }
            int len = ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
            if (len < 4 || off + len > data.length) {
                throw new IllegalArgumentException("bad record length " + len + " at byte " + off);
            }
            byte[] rec = new byte[len];
            System.arraycopy(data, off, rec, 0, len);
            out.add(rec);
            off += len;
        }
        return out;
    }

    private static boolean isSegment(byte[] rec, byte[] prefix) {
        if (rec.length < 4 + prefix.length) {
            return false;
        }
        for (int i = 0; i < 16; i++) {
            if (rec[2 + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static Integer rba(byte[] rec) {
        return ((rec[37] & 0xFF) << 8) | (rec[38] & 0xFF);
    }

    /** Loads the unload file into empty pending-authorization tables. */
    public Result load(Path file) throws IOException {
        List<byte[]> recs = records(Files.readAllBytes(file));
        int roots = 0;
        int children = 0;
        int invalid = 0;
        int skipped = 0;
        Long parent = null;
        boolean parentValid = false;
        for (byte[] rec : recs) {
            if (isSegment(rec, ROOT_PREFIX) && rec.length == ROOT_RECORD) {
                parentValid = PendingAuthSegments.validRootKey(rec, 39);
                if (!parentValid) {
                    invalid++;
                    parent = null;
                    continue;
                }
                Summary s = PendingAuthSegments.decodeSummary(rec, 39, rba(rec));
                PendingAuthTables.insertSummary(jdbc, s);
                parent = s.accountId();
                roots++;
            } else if (isSegment(rec, CHILD_PREFIX) && rec.length == CHILD_RECORD) {
                if (!parentValid || parent == null) {
                    skipped++;
                    continue;
                }
                PendingAuthTables.insertDetail(jdbc, PendingAuthSegments.decodeDetail(rec, 39, parent, rba(rec)));
                children++;
            }
        }
        return new Result(roots, children, invalid, skipped);
    }

    /** Writes the tables back in the unload format; returns {roots, children}. */
    public int[] export(Path file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER);
        int roots = 0;
        int children = 0;
        int maxTwins = 0;
        for (Summary s : PendingAuthTables.summaries(jdbc)) {
            out.write(record(ROOT_PREFIX, s.legacyRba(), PendingAuthSegments.encodeSummary(s)));
            roots++;
            int twins = 0;
            for (Detail d : PendingAuthTables.details(jdbc, s.accountId())) {
                out.write(record(CHILD_PREFIX, d.legacyRba(), PendingAuthSegments.encodeDetail(d)));
                twins++;
            }
            children += twins;
            maxTwins = Math.max(maxTwins, twins);
        }
        out.write(trailer(roots, children, maxTwins));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, out.toByteArray());
        return new int[] {roots, children};
    }

    private static byte[] record(byte[] prefix, Integer rba, byte[] segment) {
        int len = 2 + prefix.length + 2 + segment.length + 1;
        byte[] rec = new byte[len];
        rec[0] = (byte) (len >> 8);
        rec[1] = (byte) len;
        System.arraycopy(prefix, 0, rec, 2, prefix.length);
        int v = rba == null ? 0 : rba;
        rec[37] = (byte) (v >> 8);
        rec[38] = (byte) v;
        System.arraycopy(segment, 0, rec, 39, segment.length);
        return rec;
    }

    /** Statistics trailer: root count at bytes 20 and 40, largest twin chain at 28 and 56, child count at 32, 60 and 80. */
    static byte[] trailer(int roots, int children, int maxTwins) {
        byte[] t = new byte[TRAILER_LENGTH];
        byte[] fixed = HEX.parseHex("0058000000980050d7c1e4e3e2e4d4f000000001");
        System.arraycopy(fixed, 0, t, 0, fixed.length);
        System.arraycopy(HEX.parseHex("01010072d7c1e4e3c4e3d3f1"), 0, t, 44, 12);
        System.arraycopy(HEX.parseHex("020280ce"), 0, t, 84, 4);
        int4(t, 20, roots);
        int4(t, 40, roots);
        int4(t, 28, maxTwins);
        int4(t, 56, maxTwins);
        int4(t, 32, children);
        int4(t, 60, children);
        int4(t, 80, children);
        return t;
    }

    private static void int4(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
