/**
 * Copyright 2012 Twitter, Inc.
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
package parquet.bytes;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

import parquet.Log;

/**
 * utility methods to deal with bytes
 *
 * @author Julien Le Dem
 *
 */
public class BytesUtils {
  private static final Log LOG = Log.getLog(BytesUtils.class);

  public static final Charset UTF8 = Charset.forName("UTF-8");

  /**
   * give the number of bits needed to encode an int given the max value
   * @param bound max int that we want to encode
   * @return the number of bits required
   */
  public static int getWidthFromMaxInt(int bound) {
    return 32 - Integer.numberOfLeadingZeros(bound);
  }

  /**
   * reads an int in little endian at the given position
   * @param in
   * @param offset
   * @return
   * @throws IOException
   */
  public static int readIntLittleEndian(byte[] in, int offset) throws IOException {
    int ch4 = in[offset] & 0xff;
    int ch3 = in[offset + 1] & 0xff;
    int ch2 = in[offset + 2] & 0xff;
    int ch1 = in[offset + 3] & 0xff;
    return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));
  }

  public static int readIntLittleEndian(InputStream in) throws IOException {
    // TODO: this is duplicated code in LittleEndianDataInputStream
    int ch1 = in.read();
    int ch2 = in.read();
    int ch3 = in.read();
    int ch4 = in.read();
    if ((ch1 | ch2 | ch3 | ch4) < 0) {
        throw new EOFException();
    }
    return ((ch4 << 24) + (ch3 << 16) + (ch2 << 8) + (ch1 << 0));
  }

  public static int readIntLittleEndianOnOneByte(InputStream in) throws IOException {
      int ch1 = in.read();
      if (ch1 < 0) {
        throw new EOFException();
      }
      return ch1;
  }

  public static int readIntLittleEndianOnTwoBytes(InputStream in) throws IOException {
      int ch1 = in.read();
      int ch2 = in.read();
      if ((ch1 | ch2 ) < 0) {
          throw new EOFException();
      }
      return ((ch2 << 8) + (ch1 << 0));
  }

  public static int readIntLittleEndianOnThreeBytes(InputStream in) throws IOException {
      int ch1 = in.read();
      int ch2 = in.read();
      int ch3 = in.read();
      if ((ch1 | ch2 | ch3 ) < 0) {
          throw new EOFException();
      }
      return ((ch3 << 16) + (ch2 << 8) + (ch1 << 0));
  }

  public static int readIntLittleEndianPaddedOnBitWidth(InputStream in, int bitWidth)
      throws IOException {

    int bytesWidth = paddedByteCountFromBits(bitWidth);
    switch (bytesWidth) {
      case 0:
        return 0;
      case 1:
        return BytesUtils.readIntLittleEndianOnOneByte(in);
      case 2:
        return BytesUtils.readIntLittleEndianOnTwoBytes(in);
      case 3:
        return  BytesUtils.readIntLittleEndianOnThreeBytes(in);
      case 4:
        return BytesUtils.readIntLittleEndian(in);
      default:
        throw new IOException(
          String.format("Encountered bitWidth (%d) that requires more than 4 bytes", bitWidth));
    }
  }

  public static void writeIntLittleEndianOnOneByte(OutputStream out, int v) throws IOException {
    out.write((v >>>  0) & 0xFF);
  }

  public static void writeIntLittleEndianOnTwoBytes(OutputStream out, int v) throws IOException {
    out.write((v >>>  0) & 0xFF);
    out.write((v >>>  8) & 0xFF);
  }

  public static void writeIntLittleEndianOnThreeBytes(OutputStream out, int v) throws IOException {
    out.write((v >>>  0) & 0xFF);
    out.write((v >>>  8) & 0xFF);
    out.write((v >>> 16) & 0xFF);
  }

  public static void writeIntLittleEndian(OutputStream out, int v) throws IOException {
    // TODO: this is duplicated code in LittleEndianDataOutputStream
    out.write((v >>>  0) & 0xFF);
    out.write((v >>>  8) & 0xFF);
    out.write((v >>> 16) & 0xFF);
    out.write((v >>> 24) & 0xFF);
    if (Log.DEBUG) LOG.debug("write le int: " + v + " => "+ ((v >>>  0) & 0xFF) + " " + ((v >>>  8) & 0xFF) + " " + ((v >>> 16) & 0xFF) + " " + ((v >>> 24) & 0xFF));
  }

  /**
   * Write a little endian int to out, using the the number of bytes required by
   * bit width
   */
  public static void writeIntLittleEndianPaddedOnBitWidth(OutputStream out, int v, int bitWidth)
      throws IOException {

    int bytesWidth = paddedByteCountFromBits(bitWidth);
    switch (bytesWidth) {
      case 0:
        break;
      case 1:
        writeIntLittleEndianOnOneByte(out, v);
        break;
      case 2:
        writeIntLittleEndianOnTwoBytes(out, v);
        break;
      case 3:
        writeIntLittleEndianOnThreeBytes(out, v);
        break;
      case 4:
        writeIntLittleEndian(out, v);
        break;
      default:
        throw new IOException(
          String.format("Encountered value (%d) that requires more than 4 bytes", v));
    }
  }

  public static int readUnsignedVarInt(InputStream in) throws IOException {
    int value = 0;
    int i = 0;
    int b;
    while (((b = in.read()) & 0x80) != 0) {
      value |= (b & 0x7F) << i;
      i += 7;
    }
    return value | (b << i);
  }

  public static void writeUnsignedVarInt(int value, OutputStream out) throws IOException {
    while ((value & 0xFFFFFF80) != 0L) {
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    out.write(value & 0x7F);
  }

  /**
   * @param bitLength a count of bits
   * @return the corresponding byte count padded to the next byte
   */
  public static int paddedByteCountFromBits(int bitLength) {
    return (bitLength + 7) / 8;
  }
}
