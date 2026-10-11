package com.serena.syncora.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Takes MPEG-TS — the `.ts` segments most HLS streams are made of — and hands out only its audio
 * elementary stream: the ADTS/AAC or MP3 frames, without the 188-byte transport packets, without the
 * program tables and without the video a TV channel would carry next to them. That is what lets a
 * video HLS link play as audio only.
 *
 * <p>Reads the PAT and PMT to find the audio PID, then strips the PES headers of that PID. It
 * resynchronises after damaged bytes and keeps going when the station republishes its tables.
 */
final class MpegTsReader extends InputStream {

    private static final int PACKET_SIZE = 188;
    private static final int MAX_PROGRAM_PIDS = 8;

    /** ISO/IEC 13818-1 stream types: MP3 and ADTS AAC arrive as the elementary streams Syncora reads. */
    private static final int TYPE_MP3_11172 = 0x03;
    private static final int TYPE_MP3_13818 = 0x04;
    private static final int TYPE_AAC_ADTS = 0x0F;
    private static final int TYPE_AAC_LATM = 0x11;

    private final InputStream in;
    private final byte[] packet = new byte[PACKET_SIZE];
    private final ByteArrayOutputStream audio = new ByteArrayOutputStream();

    private final int[] programPids = new int[MAX_PROGRAM_PIDS];
    private int programCount;
    private int pmtOwner = -1;
    private int audioPid = -1;
    private int audioType = -1;
    private String unsupported = "";
    /** Bytes of a PES header that spilled into the following packets. */
    private int pesSkip;

    private byte[] out = new byte[0];
    private int outPos;
    private boolean endOfStream;

    MpegTsReader(InputStream in) {
        this.in = in;
    }

    /** True when the input begins on an MPEG-TS packet boundary. Leaves all bytes buffered. */
    static boolean looksLikeTransportStream(PeekStream stream) throws IOException {
        for (int offset = 0; offset < PACKET_SIZE; offset++) {
            if (!stream.ensure(offset + 1)) return false;
            if (stream.peek(offset) != 0x47) continue;
            if (stream.ensure(offset + PACKET_SIZE + 1) && stream.peek(offset + PACKET_SIZE) == 0x47) {
                return true;
            }
        }
        return false;
    }

    /** The stream type of the audio being decoded (0 until the first PMT arrived). */
    int audioStreamType() {
        return audioType;
    }

    /** "AAC in MPEG-TS" once known, or what makes the stream unplayable. */
    String describe() {
        switch (audioType) {
            case TYPE_AAC_ADTS:
                return "AAC in MPEG-TS";
            case TYPE_MP3_11172:
            case TYPE_MP3_13818:
                return "MP3 in MPEG-TS";
            case TYPE_AAC_LATM:
                return "AAC (LATM) in MPEG-TS";
            default:
                if (audioType > 0) return "MPEG-TS audio, type 0x" + Integer.toHexString(audioType);
                return unsupported.isEmpty() ? "MPEG-TS" : unsupported;
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        int done = 0;
        while (done < length) {
            if (outPos >= out.length) {
                if (endOfStream || !fill()) {
                    endOfStream = true;
                    break;
                }
            }
            int n = Math.min(length - done, out.length - outPos);
            System.arraycopy(out, outPos, buffer, offset + done, n);
            outPos += n;
            done += n;
            // Hand back what a PES packet carried instead of waiting for the caller's whole buffer.
            if (done >= 4096) break;
        }
        return done == 0 ? -1 : done;
    }

    /** Reads transport packets until some audio bytes are ready, or the input ends. */
    private boolean fill() throws IOException {
        audio.reset();
        while (audio.size() == 0) {
            if (!nextPacket()) return false;
            handlePacket();
        }
        out = audio.toByteArray();
        outPos = 0;
        return true;
    }

    /** Reads one packet, sliding forward to a 0x47 sync byte when the packet grid was lost. */
    private boolean nextPacket() throws IOException {
        while (true) {
            if (!readFully(packet, 0, PACKET_SIZE)) return false;
            if (packet[0] == 0x47) return true;
            int shift = 1;
            while (shift < PACKET_SIZE && packet[shift] != 0x47) shift++;
            if (shift >= PACKET_SIZE) continue;
            System.arraycopy(packet, shift, packet, 0, PACKET_SIZE - shift);
            if (!readFully(packet, PACKET_SIZE - shift, shift)) return false;
        }
    }

    private boolean readFully(byte[] buffer, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n = in.read(buffer, offset + done, length - done);
            if (n < 0) return false;
            done += n;
        }
        return true;
    }

    private void handlePacket() throws IOException {
        int b1 = packet[1] & 0xFF;
        int b3 = packet[3] & 0xFF;
        if ((b1 & 0x80) != 0) return; // this packet arrived damaged
        int pid = ((b1 & 0x1F) << 8) | (packet[2] & 0xFF);
        int adaptation = (b3 >> 4) & 3;
        int index = 4;
        if ((adaptation & 2) != 0) {
            index += 1 + (packet[index] & 0xFF);
            if (index >= PACKET_SIZE) return;
        }
        if ((adaptation & 1) == 0) return; // adaptation field only, no payload

        if (pid == 0) {
            readPat(index);
            return;
        }
        if (isProgramPid(pid)) {
            readPmt(pid, index);
            return;
        }
        if (pid != audioPid) return; // video, teletext, another programme: not ours
        if (((b3 >> 6) & 3) != 0) {
            throw new IOException("This station encrypts its audio inside MPEG-TS, which Syncora cannot open");
        }
        readPes(index, (b1 & 0x40) != 0);
    }

    private boolean isProgramPid(int pid) {
        for (int i = 0; i < programCount; i++) {
            if (programPids[i] == pid) return true;
        }
        return false;
    }

    /** Program association table: which PID carries the programme map of each programme. */
    private void readPat(int index) {
        int at = sectionStart(index, 0x00);
        if (at < 0) return;
        int end = sectionEnd(at);
        for (int p = at + 8; p + 3 < end; p += 4) {
            int program = ((packet[p] & 0xFF) << 8) | (packet[p + 1] & 0xFF);
            int pmtPid = ((packet[p + 2] & 0x1F) << 8) | (packet[p + 3] & 0xFF);
            if (program == 0 || pmtPid <= 0) continue;
            boolean known = false;
            for (int i = 0; i < programCount; i++) known |= programPids[i] == pmtPid;
            if (!known && programCount < MAX_PROGRAM_PIDS) programPids[programCount++] = pmtPid;
        }
    }

    /** Program map table: which PID carries audio, and in which codec. */
    private void readPmt(int pid, int index) {
        if (audioPid >= 0 && pid != pmtOwner) return; // already decoding audio of another programme
        int at = sectionStart(index, 0x02);
        if (at < 0 || at + 12 >= PACKET_SIZE) return;
        int end = sectionEnd(at);
        int programInfoLength = ((packet[at + 10] & 0x0F) << 8) | (packet[at + 11] & 0xFF);
        int fallbackPid = -1;
        int fallbackType = 0;
        for (int p = at + 12 + programInfoLength; p + 4 < end; ) {
            int type = packet[p] & 0xFF;
            int elementaryPid = ((packet[p + 1] & 0x1F) << 8) | (packet[p + 2] & 0xFF);
            int infoLength = ((packet[p + 3] & 0x0F) << 8) | (packet[p + 4] & 0xFF);
            p += 5 + infoLength;
            if (!isAudio(type)) continue;
            if (isDecodable(type)) {
                audioPid = elementaryPid;
                audioType = type;
                pmtOwner = pid;
                unsupported = "";
                return;
            }
            if (fallbackPid < 0) {
                fallbackPid = elementaryPid;
                fallbackType = type;
            }
        }
        if (audioPid < 0 && fallbackPid >= 0) {
            // Nothing decodable: still pick it up so the reason can be reported instead of silence.
            unsupported = describeType(fallbackType);
            audioPid = fallbackPid;
            audioType = fallbackType;
            pmtOwner = pid;
        }
    }

    private static String describeType(int type) {
        switch (type) {
            case TYPE_AAC_LATM:
                return "AAC in LATM packaging, which Syncora cannot open";
            case 0x81:
            case 0x87:
                return "AC-3 surround audio, which Syncora cannot open";
            case 0x06:
                return "private MPEG-TS audio (type 0x06), which Syncora cannot open";
            default:
                return "audio type 0x" + Integer.toHexString(type) + ", which Syncora cannot open";
        }
    }

    private static boolean isAudio(int type) {
        return type == TYPE_MP3_11172 || type == TYPE_MP3_13818 || type == TYPE_AAC_ADTS || type == TYPE_AAC_LATM
                || type == 0x06 || type == 0x81 || type == 0x87;
    }

    /** MP3 and ADTS AAC leave the demuxer as the same elementary streams a radio server would send. */
    private static boolean isDecodable(int type) {
        return type == TYPE_MP3_11172 || type == TYPE_MP3_13818 || type == TYPE_AAC_ADTS;
    }

    /** Start of a PSI section body, or -1 when this packet does not hold one. */
    private int sectionStart(int index, int tableId) {
        if (index >= PACKET_SIZE) return -1;
        int at = index + 1 + (packet[index] & 0xFF); // pointer field
        if (at + 3 >= PACKET_SIZE || (packet[at] & 0xFF) != tableId) return -1;
        return at;
    }

    /** Last byte of a section body, minus its CRC and clamped to the packet. */
    private int sectionEnd(int at) {
        int sectionLength = ((packet[at + 1] & 0x0F) << 8) | (packet[at + 2] & 0xFF);
        return Math.min(PACKET_SIZE - 4, at + 3 + sectionLength - 4);
    }

    /**
     * Packetised elementary stream. At a start indicator the PES header is parsed and skipped; the
     * rest of the packet — and every following packet of the same PES packet — is audio.
     */
    private void readPes(int index, boolean start) {
        int from = index;
        if (start) {
            pesSkip = 0;
            if (index + 9 > PACKET_SIZE) return;
            if (packet[index] != 0 || packet[index + 1] != 0 || packet[index + 2] != 1) return;
            if ((packet[index + 3] & 0xFF) < 0xBC) return; // not an audio or video PES packet
            from = index + 9 + (packet[index + 8] & 0xFF);
            if (from > PACKET_SIZE) {
                pesSkip = from - PACKET_SIZE; // a long header: the rest of it is in the next packets
                return;
            }
        }
        if (pesSkip > 0) {
            int skip = Math.min(pesSkip, PACKET_SIZE - from);
            pesSkip -= skip;
            from += skip;
        }
        if (from < PACKET_SIZE) audio.write(packet, from, PACKET_SIZE - from);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
