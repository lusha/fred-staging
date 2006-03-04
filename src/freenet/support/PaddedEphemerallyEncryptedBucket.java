package freenet.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;

/**
 * A proxy Bucket which adds:
 * - Encryption with the supplied cipher, and a random, ephemeral key.
 * - Padding to the next PO2 size.
 */
public class PaddedEphemerallyEncryptedBucket implements Bucket {

	private final Bucket bucket;
	private final int minPaddedSize;
	private final RandomSource origRandom;
	private final Rijndael aes;
	/** The decryption key. May be null. */
	private final byte[] key;
	private long dataLength;
	private boolean readOnly;
	private int lastOutputStream;
	
	/**
	 * Create a padded encrypted proxy bucket.
	 * @param bucket The bucket which we are proxying to. Must be empty.
	 * @param pcfb The encryption mode with which to encipher/decipher the data.
	 * @param minSize The minimum padded size of the file (after it has been closed).
	 * @param origRandom Hard random number generator from which to obtain a seed for padding.
	 * @throws UnsupportedCipherException 
	 */
	public PaddedEphemerallyEncryptedBucket(Bucket bucket, int minSize, RandomSource origRandom, boolean forgetKey) {
		this.origRandom = origRandom;
		this.bucket = bucket;
		if(bucket.size() != 0) throw new IllegalArgumentException("Bucket must be empty");
		try {
			aes = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new Error(e);
		}
		byte[] key = new byte[32];
		origRandom.nextBytes(key);
		aes.initialize(key);
		if(forgetKey) {
			// Might as well blank it
			for(int i=0;i<key.length;i++) key[i] = 0;
			this.key = null;
		} else {
			this.key = key;
		}
		this.minPaddedSize = minSize;
		readOnly = false;
		lastOutputStream = 0;
	}

	/**
	 * Load an existing PaddedEphemerallyEncryptedBucket, with a key.
	 * The bucket can and should already exist.
	 * @param bucket
	 * @param minSize
	 * @param knownSize The size of the data. This cannot be deduced from the bucket
	 * alone and must be specified. If the bucket is smaller than this, we throw.
	 * @param key
	 * @param origRandom
	 * @throws IOException 
	 */
	public PaddedEphemerallyEncryptedBucket(Bucket bucket, int minSize, long knownSize, byte[] key, RandomSource origRandom) throws IOException {
		if(bucket.size() < knownSize)
			throw new IOException("Bucket is too small on disk");
		this.dataLength = knownSize;
		this.origRandom = origRandom;
		this.bucket = bucket;
		try {
			aes = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new Error(e);
		}
		aes.initialize(key);
		this.key = key;
		this.minPaddedSize = minSize;
		readOnly = false;
		lastOutputStream = 0;
	}

	public OutputStream getOutputStream() throws IOException {
		if(readOnly) throw new IOException("Read only");
		OutputStream os = bucket.getOutputStream();
		dataLength = 0;
		return new PaddedEphemerallyEncryptedOutputStream(os, ++lastOutputStream);
	}

	private class PaddedEphemerallyEncryptedOutputStream extends OutputStream {

		final PCFBMode pcfb;
		final OutputStream out;
		final int streamNumber;
		
		public PaddedEphemerallyEncryptedOutputStream(OutputStream out, int streamNumber) {
			this.out = out;
			dataLength = 0;
			this.streamNumber = streamNumber;
			pcfb = new PCFBMode(aes);
		}
		
		public void write(int b) throws IOException {
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			if(b < 0 || b > 255)
				throw new IllegalArgumentException();
			int toWrite = pcfb.encipher(b);
			synchronized(PaddedEphemerallyEncryptedBucket.this) {
				out.write(toWrite);
				dataLength++;
			}
		}
		
		public void write(byte[] buf, int offset, int length) throws IOException {
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			byte[] enc = new byte[length];
			System.arraycopy(buf, offset, enc, 0, length);
			pcfb.blockEncipher(enc, 0, enc.length);
			synchronized(PaddedEphemerallyEncryptedBucket.this) {
				out.write(enc, 0, enc.length);
				dataLength += enc.length;
			}
		}
		
		// Override this or FOS will use write(int)
		public void write(byte[] buf) throws IOException {
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			write(buf, 0, buf.length);
		}
		
		public void close() throws IOException {
			try {
				if(streamNumber != lastOutputStream) {
					Logger.normal(this, "Not padding out to length because have been superceded: "+getName());
					return;
				}
				synchronized(PaddedEphemerallyEncryptedBucket.this) {
					MersenneTwister paddingSource = new MersenneTwister(origRandom.nextLong());
					long finalLength = paddedLength();
					long padding = finalLength - dataLength;
					byte[] buf = new byte[4096];
					long writtenPadding = 0;
					while(writtenPadding < padding) {
						int left = Math.min((int) (padding - writtenPadding), buf.length);
						paddingSource.nextBytes(buf);
						out.write(buf, 0, left);
						writtenPadding += left;
					}
				}
			} finally {
				out.close();
			}
		}
	}

	public InputStream getInputStream() throws IOException {
		return new PaddedEphemerallyEncryptedInputStream(bucket.getInputStream());
	}

	private class PaddedEphemerallyEncryptedInputStream extends InputStream {

		final InputStream in;
		final PCFBMode pcfb;
		long ptr;
		
		public PaddedEphemerallyEncryptedInputStream(InputStream in) {
			this.in = in;
			pcfb = new PCFBMode(aes);
			ptr = 0;
		}
		
		public int read() throws IOException {
			if(ptr > dataLength) return -1;
			int x = in.read();
			if(x == -1) return x;
			ptr++;
			return pcfb.decipher(x);
		}
		
		public final int available() {
			int x = (int)(dataLength - ptr);
			return (x < 0) ? 0 : x;
		}
		
		public int read(byte[] buf, int offset, int length) throws IOException {
			// FIXME remove debugging
			if(length+offset > buf.length || offset < 0 || length < 0)
				throw new ArrayIndexOutOfBoundsException("a="+offset+", b="+length+", length "+buf.length);
			int x = available();
			if(x <= 0) return -1;
			length = Math.min(length, x);
			int readBytes = in.read(buf, offset, length);
			if(readBytes <= 0) return readBytes;
			ptr += readBytes;
			pcfb.blockDecipher(buf, offset, readBytes);
			return readBytes;
		}

		public int read(byte[] buf) throws IOException {
			return read(buf, 0, buf.length);
		}
		
		public long skip(long bytes) throws IOException {
			byte[] buf = new byte[(int)Math.min(4096, bytes)];
			long skipped = 0;
			while(skipped < bytes) {
				int x = read(buf, 0, (int)Math.min(bytes-skipped, buf.length));
				if(x <= 0) return skipped;
				skipped += x;
			}
			return skipped;
		}
		
		public void close() throws IOException {
			in.close();
		}
	}

	/**
	 * Return the length of the data in the proxied bucket, after padding.
	 */
	public synchronized long paddedLength() {
		long size = dataLength;
		if(size < minPaddedSize) size = minPaddedSize;
		if(size == minPaddedSize) return size;
		long min = minPaddedSize;
		long max = minPaddedSize << 1;
		while(true) {
			if(max < 0)
				throw new Error("Impossible size: "+size+" - min="+min+", max="+max);
			if(size < min)
				throw new IllegalStateException("???");
			if(size >= min && size <= max) {
				Logger.minor(this, "Padded: "+max+" was: "+dataLength+" for "+getName());
				return max;
			}
			min = max;
			max = max << 1;
		}
	}

	public String getName() {
		return "Encrypted:"+bucket.getName();
	}

	public long size() {
		return dataLength;
	}

	public boolean isReadOnly() {
		return readOnly;
	}
	
	public void setReadOnly() {
		readOnly = true;
	}

	/**
	 * @return The underlying Bucket.
	 */
	public Bucket getUnderlying() {
		return bucket;
	}

	public void free() {
		bucket.free();
	}

	/**
	 * Get the decryption key. May have been blanked out.
	 */
	public byte[] getKey() {
		return key;
	}

}
