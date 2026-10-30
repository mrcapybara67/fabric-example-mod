package com.capy.client.gltf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A binary glTF (GLB) container, split into the two chunks it holds.
 *
 * <p>A GLB is a 12 byte header followed by chunks, all little endian:
 *
 * <pre>
 *   header   int32 magic 'glTF' (0x46546C67), int32 version 2, int32 total length
 *   chunk    int32 length, int32 type, byte[length] data
 * </pre>
 *
 * <p>The first chunk is the JSON document, type {@code JSON} (0x4E4F534A); the
 * optional second is the binary buffer, type {@code BIN\0} (0x004E4942). Both
 * chunk lengths include their padding to a four byte boundary — JSON padded
 * with spaces, BIN with zeros — so the chunks can simply be stepped over.
 *
 * <p>This class only splits the container. It does not interpret either chunk,
 * which keeps the malformed-file checks in one place: a file that gets past the
 * constructor has a readable JSON document, and every later failure is a
 * problem with the document rather than with the wrapper.
 */
public final class GltfBuffer {
	private static final int MAGIC = 0x46546C67;
	private static final int VERSION = 2;
	private static final int CHUNK_JSON = 0x4E4F534A;
	private static final int CHUNK_BIN = 0x004E4942;
	private static final int HEADER_BYTES = 12;
	private static final int CHUNK_HEADER_BYTES = 8;

	private final String json;
	private final ByteBuffer bin;

	public GltfBuffer(byte[] glbBytes) throws IOException {
		if (glbBytes == null || glbBytes.length < HEADER_BYTES + CHUNK_HEADER_BYTES) {
			throw new IOException("not a GLB: file is too short");
		}
		ByteBuffer file = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN);
		int magic = file.getInt();
		if (magic != MAGIC) {
			throw new IOException("not a GLB: bad magic 0x" + Integer.toHexString(magic));
		}
		int version = file.getInt();
		if (version != VERSION) {
			// glTF 1 was a different format entirely; nothing here can read it.
			throw new IOException("unsupported GLB version " + version);
		}
		int declared = file.getInt();
		if (declared < HEADER_BYTES || declared > glbBytes.length) {
			// A truncated download is the likeliest cause of this one, so it is
			// reported rather than silently tolerated.
			throw new IOException("GLB length " + declared + " does not match " + glbBytes.length + " bytes");
		}

		String foundJson = null;
		ByteBuffer foundBin = null;
		int offset = HEADER_BYTES;
		while (offset + CHUNK_HEADER_BYTES <= declared) {
			file.position(offset);
			int chunkLength = file.getInt();
			int chunkType = file.getInt();
			int dataStart = offset + CHUNK_HEADER_BYTES;
			if (chunkLength < 0 || dataStart + chunkLength > declared) {
				throw new IOException("GLB chunk overruns the file at offset " + offset);
			}
			if (chunkType == CHUNK_JSON && foundJson == null) {
				byte[] text = new byte[chunkLength];
				System.arraycopy(glbBytes, dataStart, text, 0, chunkLength);
				foundJson = trimPadding(new String(text, StandardCharsets.UTF_8));
			} else if (chunkType == CHUNK_BIN && foundBin == null) {
				// A slice, not a copy: the binary chunk can be megabytes and the
				// accessors read it by absolute position anyway.
				ByteBuffer slice = ByteBuffer.wrap(glbBytes, dataStart, chunkLength).slice();
				foundBin = slice.order(ByteOrder.LITTLE_ENDIAN);
			}
			// Unknown chunk types are allowed by the spec and skipped.
			offset = dataStart + chunkLength;
		}
		if (foundJson == null) {
			throw new IOException("GLB has no JSON chunk");
		}
		this.json = foundJson;
		this.bin = foundBin == null ? ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN) : foundBin;
	}

	/** Trailing space or NUL padding, which would otherwise reach the JSON parser. */
	private static String trimPadding(String text) {
		int end = text.length();
		while (end > 0) {
			char c = text.charAt(end - 1);
			if (c != '\0' && !Character.isWhitespace(c)) {
				break;
			}
			end--;
		}
		return text.substring(0, end);
	}

	/** The JSON chunk, as text. */
	public String getJson() {
		return json;
	}

	/**
	 * The binary chunk, little endian. Empty when the file has none, which is
	 * legal: a GLB may keep its buffers outside the file.
	 *
	 * <p>A duplicate is returned so that a caller walking the buffer cannot move
	 * the position out from under the next one.
	 */
	public ByteBuffer getBin() {
		return bin.duplicate().order(ByteOrder.LITTLE_ENDIAN);
	}
}
