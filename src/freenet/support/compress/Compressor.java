/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * A data compressor. Contains methods to get all data compressors.
 * This is for single-file compression (gzip, bzip2) as opposed to archives.
 */
public interface Compressor {

	public enum COMPRESSOR_TYPE implements Compressor {
		// They will be tried in order: put the less resource consuming first
		GZIP("GZIP", new GzipCompressor(), (short) 0),
		BZIP2("BZIP2", new Bzip2Compressor(), (short) 1),
		LZMA("LZMA", new LZMACompressor(), (short)2);
		
		public final String name;
		public final Compressor compressor;
		public final short metadataID;
		
		COMPRESSOR_TYPE(String name, Compressor c, short metadataID) {
			this.name = name;
			this.compressor = c;
			this.metadataID = metadataID;
		}
		
		public static COMPRESSOR_TYPE getCompressorByMetadataID(short id) {
			COMPRESSOR_TYPE[] values = values();
			for(COMPRESSOR_TYPE current : values)
				if(current.metadataID == id)
					return current;
			return null;
		}

		public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
			return compressor.compress(data, bf, maxReadLength, maxWriteLength);
		}

		public Bucket decompress(Bucket data, BucketFactory bucketFactory, long maxLength, long maxEstimateSizeLength, Bucket preferred) throws IOException, CompressionOutputSizeException {
			return compressor.decompress(data, bucketFactory, maxLength, maxEstimateSizeLength, preferred);
		}

		public int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException {
			return compressor.decompress(dbuf, i, j, output);
		}
		
	}

	/**
	 * Compress the data.
	 * @param data The bucket to read from.
	 * @param bf The means to create a new bucket.
	 * @param maxReadLength The maximum number of bytes to read from the input bucket.
	 * @param maxWriteLength The maximum number of bytes to write to the output bucket. If this is exceeded, throw a CompressionOutputSizeException.
	 * @return The compressed data.
	 * @throws IOException If an error occurs reading or writing data.
	 * @throws CompressionOutputSizeException If the compressed data is larger than maxWriteLength. 
	 */
	public abstract Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException;

	/**
	 * Decompress data.
	 * @param data The data to decompress.
	 * @param bucketFactory A BucketFactory to create a new Bucket with if necessary.
	 * @param maxLength The maximum length to decompress (we throw if more is present).
	 * @param maxEstimateSizeLength If the data is too big, and this is >0, read up to this many bytes in order to try to get the data size.
	 * @param preferred A Bucket to use instead. If null, we allocate one from the BucketFactory.
	 * @return
	 * @throws IOException
	 * @throws CompressionOutputSizeException
	 */
	public abstract Bucket decompress(Bucket data, BucketFactory bucketFactory, long maxLength, long maxEstimateSizeLength, Bucket preferred) throws IOException, CompressionOutputSizeException;

	/** Decompress in RAM only.
	 * @param dbuf Input buffer.
	 * @param i Offset to start reading from.
	 * @param j Number of bytes to read.
	 * @param output Output buffer.
	 * @throws DecompressException 
	 * @throws CompressionOutputSizeException 
	 * @returns The number of bytes actually written.
	 */
	public abstract int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException;
}
