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
package parquet.hadoop;

import static parquet.Log.DEBUG;
import static parquet.format.Util.writeFileMetaData;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import parquet.Log;
import parquet.Version;
import parquet.bytes.BytesInput;
import parquet.bytes.BytesUtils;
import parquet.column.ColumnDescriptor;
import parquet.column.page.DictionaryPage;
import parquet.format.converter.ParquetMetadataConverter;
import parquet.hadoop.metadata.BlockMetaData;
import parquet.hadoop.metadata.ColumnChunkMetaData;
import parquet.hadoop.metadata.ColumnPath;
import parquet.hadoop.metadata.CompressionCodecName;
import parquet.hadoop.metadata.FileMetaData;
import parquet.hadoop.metadata.ParquetMetadata;
import parquet.io.ParquetEncodingException;
import parquet.schema.MessageType;
import parquet.schema.PrimitiveType.PrimitiveTypeName;

/**
 * Internal implementation of the Parquet file writer as a block container
 *
 * @author Julien Le Dem
 *
 */
public class ParquetFileWriter {
  private static final Log LOG = Log.getLog(ParquetFileWriter.class);

  public static final String PARQUET_METADATA_FILE = "_metadata";
  public static final byte[] MAGIC = "PAR1".getBytes(Charset.forName("ASCII"));
  public static final int CURRENT_VERSION = 1;

  private static final ParquetMetadataConverter metadataConverter = new ParquetMetadataConverter();

  private final MessageType schema;
  private final FSDataOutputStream out;
  private BlockMetaData currentBlock;
  private ColumnChunkMetaData currentColumn;
  private long currentRecordCount;
  private List<BlockMetaData> blocks = new ArrayList<BlockMetaData>();
  private long uncompressedLength;
  private long compressedLength;
  private Set<parquet.column.Encoding> currentEncodings;

  private CompressionCodecName currentChunkCodec;
  private ColumnPath currentChunkPath;
  private PrimitiveTypeName currentChunkType;
  private long currentChunkFirstDataPage;
  private long currentChunkDictionaryPageOffset;
  private long currentChunkValueCount;

  /**
   * Captures the order in which methods should be called
   *
   * @author Julien Le Dem
   *
   */
  private enum STATE {
    NOT_STARTED {
      STATE start() {
        return STARTED;
      }
    },
    STARTED {
      STATE startBlock() {
        return BLOCK;
      }
      STATE end() {
        return ENDED;
      }
    },
    BLOCK  {
      STATE startColumn() {
        return COLUMN;
      }
      STATE endBlock() {
        return STARTED;
      }
    },
    COLUMN {
      STATE endColumn() {
        return BLOCK;
      };
      STATE write() {
        return this;
      }
    },
    ENDED;

    STATE start() {throw new IllegalStateException(this.name());}
    STATE startBlock() {throw new IllegalStateException(this.name());}
    STATE startColumn() {throw new IllegalStateException(this.name());}
    STATE write() {throw new IllegalStateException(this.name());}
    STATE endColumn() {throw new IllegalStateException(this.name());}
    STATE endBlock() {throw new IllegalStateException(this.name());}
    STATE end() {throw new IllegalStateException(this.name());}
  }

  private STATE state = STATE.NOT_STARTED;

  /**
   *
   * @param schema the schema of the data
   * @param out the file to write to
   * @param codec the codec to use to compress blocks
   * @throws IOException if the file can not be created
   */
  public ParquetFileWriter(Configuration configuration, MessageType schema, Path file) throws IOException {
    super();
    this.schema = schema;
    FileSystem fs = file.getFileSystem(configuration);
    this.out = fs.create(file, false);
  }

  /**
   * start the file
   * @throws IOException
   */
  public void start() throws IOException {
    state = state.start();
    if (DEBUG) LOG.debug(out.getPos() + ": start");
    out.write(MAGIC);
  }

  /**
   * start a block
   * @param recordCount the record count in this block
   * @throws IOException
   */
  public void startBlock(long recordCount) throws IOException {
    state = state.startBlock();
    if (DEBUG) LOG.debug(out.getPos() + ": start block");
//    out.write(MAGIC); // TODO: add a magic delimiter
    currentBlock = new BlockMetaData();
    currentRecordCount = recordCount;
  }

  /**
   * start a column inside a block
   * @param descriptor the column descriptor
   * @param valueCount the value count in this column
   * @param compressionCodecName
   * @throws IOException
   */
  public void startColumn(ColumnDescriptor descriptor, long valueCount, CompressionCodecName compressionCodecName) throws IOException {
    state = state.startColumn();
    if (DEBUG) LOG.debug(out.getPos() + ": start column: " + descriptor + " count=" + valueCount);
    currentEncodings = new HashSet<parquet.column.Encoding>();
    currentChunkPath = ColumnPath.get(descriptor.getPath());
    currentChunkType = descriptor.getType();
    currentChunkCodec = compressionCodecName;
    currentChunkValueCount = valueCount;
    currentChunkFirstDataPage = out.getPos();
    compressedLength = 0;
    uncompressedLength = 0;
  }

  /**
   * writes a dictionary page page
   * @param dictionaryPage the dictionary page
   */
  public void writeDictionaryPage(DictionaryPage dictionaryPage) throws IOException {
    state = state.write();
    if (DEBUG) LOG.debug(out.getPos() + ": write dictionary page: " + dictionaryPage.getDictionarySize() + " values");
    currentChunkDictionaryPageOffset = out.getPos();
    int uncompressedSize = dictionaryPage.getUncompressedSize();
    int compressedPageSize = (int)dictionaryPage.getBytes().size(); // TODO: fix casts
    metadataConverter.writeDictionaryPageHeader(
        uncompressedSize,
        compressedPageSize,
        dictionaryPage.getDictionarySize(),
        dictionaryPage.getEncoding(),
        out);
    this.uncompressedLength += uncompressedSize;
    this.compressedLength += compressedPageSize;
    if (DEBUG) LOG.debug(out.getPos() + ": write dictionary page content " + compressedPageSize);
    dictionaryPage.getBytes().writeAllTo(out);
    currentEncodings.add(dictionaryPage.getEncoding());
  }

  /**
   * writes a single page
   * @param valueCount count of values
   * @param uncompressedPageSize the size of the data once uncompressed
   * @param bytes the compressed data for the page without header
   * @param rlEncoding encoding of the repetition level
   * @param dlEncoding encoding of the definition level
   * @param valuesEncoding encoding of values
   */
  public void writeDataPage(
      int valueCount, int uncompressedPageSize,
      BytesInput bytes,
      parquet.column.Encoding rlEncoding,
      parquet.column.Encoding dlEncoding,
      parquet.column.Encoding valuesEncoding) throws IOException {
    state = state.write();
    long beforeHeader = out.getPos();
    if (DEBUG) LOG.debug(beforeHeader + ": write data page: " + valueCount + " values");
    int compressedPageSize = (int)bytes.size();
    metadataConverter.writeDataPageHeader(
        uncompressedPageSize, compressedPageSize,
        valueCount,
        rlEncoding,
        dlEncoding,
        valuesEncoding,
        out);
    long headerSize = out.getPos() - beforeHeader;
    this.uncompressedLength += uncompressedPageSize + headerSize;
    this.compressedLength += compressedPageSize + headerSize;
    if (DEBUG) LOG.debug(out.getPos() + ": write data page content " + compressedPageSize);
    bytes.writeAllTo(out);
    currentEncodings.add(rlEncoding);
    currentEncodings.add(dlEncoding);
    currentEncodings.add(valuesEncoding);
  }

  /**
   * writes a number of pages at once
   * @param bytes bytes to be written including page headers
   * @param uncompressedTotalPageSize total uncompressed size (without page headers)
   * @param compressedTotalPageSize total compressed size (without page headers)
   * @throws IOException
   */
   void writeDataPages(BytesInput bytes, long uncompressedTotalPageSize, long compressedTotalPageSize, List<parquet.column.Encoding> encodings) throws IOException {
    state = state.write();
    if (DEBUG) LOG.debug(out.getPos() + ": write data pages");
    long headersSize = bytes.size() - compressedTotalPageSize;
    this.uncompressedLength += uncompressedTotalPageSize + headersSize;
    this.compressedLength += compressedTotalPageSize + headersSize;
    if (DEBUG) LOG.debug(out.getPos() + ": write data pages content");
    bytes.writeAllTo(out);
    currentEncodings.addAll(encodings);
  }

  /**
   * end a column (once all rep, def and data have been written)
   * @throws IOException
   */
  public void endColumn() throws IOException {
    state = state.endColumn();
    if (DEBUG) LOG.debug(out.getPos() + ": end column");
    currentBlock.addColumn(ColumnChunkMetaData.get(
        currentChunkPath,
        currentChunkType,
        currentChunkCodec,
        currentEncodings,
        currentChunkFirstDataPage,
        currentChunkDictionaryPageOffset,
        currentChunkValueCount,
        compressedLength,
        uncompressedLength));
    if (DEBUG) LOG.info("ended Column chumk: " + currentColumn);
    currentColumn = null;
    this.currentBlock.setTotalByteSize(currentBlock.getTotalByteSize() + uncompressedLength);
    this.uncompressedLength = 0;
    this.compressedLength = 0;
  }

  /**
   * ends a block once all column chunks have been written
   * @throws IOException
   */
  public void endBlock() throws IOException {
    state = state.endBlock();
    if (DEBUG) LOG.debug(out.getPos() + ": end block");
    currentBlock.setRowCount(currentRecordCount);
    blocks.add(currentBlock);
    currentBlock = null;
  }

  /**
   * ends a file once all blocks have been written.
   * closes the file.
   * @param extraMetaData the extra meta data to write in the footer
   * @throws IOException
   */
  public void end(Map<String, String> extraMetaData) throws IOException {
    state = state.end();
    if (DEBUG) LOG.debug(out.getPos() + ": end");
    ParquetMetadata footer = new ParquetMetadata(new FileMetaData(schema, extraMetaData, Version.FULL_VERSION), blocks);
    serializeFooter(footer, out);
    out.close();
  }

  private static void serializeFooter(ParquetMetadata footer, FSDataOutputStream out) throws IOException {
    long footerIndex = out.getPos();
    parquet.format.FileMetaData parquetMetadata = new ParquetMetadataConverter().toParquetMetadata(CURRENT_VERSION, footer);
    writeFileMetaData(parquetMetadata, out);
    if (DEBUG) LOG.debug(out.getPos() + ": footer length = " + (out.getPos() - footerIndex));
    BytesUtils.writeIntLittleEndian(out, (int)(out.getPos() - footerIndex));
    out.write(MAGIC);
  }

  public static void writeMetadataFile(Configuration configuration, Path outputPath, List<Footer> footers) throws IOException {
    Path metaDataPath = new Path(outputPath, PARQUET_METADATA_FILE);
    FileSystem fs = outputPath.getFileSystem(configuration);
    outputPath = outputPath.makeQualified(fs);
    FSDataOutputStream metadata = fs.create(metaDataPath);
    metadata.write(MAGIC);
    ParquetMetadata metadataFooter = mergeFooters(outputPath, footers);
    serializeFooter(metadataFooter, metadata);
    metadata.close();
  }

  private static ParquetMetadata mergeFooters(Path root, List<Footer> footers) {
    String rootPath = root.toString();
    FileMetaData fileMetaData = null;
    List<BlockMetaData> blocks = new ArrayList<BlockMetaData>();
    for (Footer footer : footers) {
      String path = footer.getFile().toString();
      if (!path.startsWith(rootPath)) {
        throw new ParquetEncodingException(path + " invalid: all the files must be contained in the root " + root);
      }
      path = path.substring(rootPath.length());
      while (path.startsWith("/")) {
        path = path.substring(1);
      }
      fileMetaData = mergeInto(footer.getParquetMetadata().getFileMetaData(), fileMetaData);
      for (BlockMetaData block : footer.getParquetMetadata().getBlocks()) {
        block.setPath(path);
        blocks.add(block);
      }
    }
    return new ParquetMetadata(fileMetaData, blocks);
  }

  /**
   * @return the current position in the underlying file
   * @throws IOException
   */
  public long getPos() throws IOException {
    return out.getPos();
  }

  static FileMetaData mergeInto(
      FileMetaData toMerge,
      FileMetaData mergedMetadata) {
    if (mergedMetadata == null) {
      return new FileMetaData(
          toMerge.getSchema(),
          new HashMap<String, String>(toMerge.getKeyValueMetaData()),
          Version.FULL_VERSION);
    } else if (
        (mergedMetadata.getSchema() == null && toMerge.getSchema() != null)
        || (mergedMetadata.getSchema() != null && !mergedMetadata.getSchema().equals(toMerge.getSchema()))) {
      throw new RuntimeException("could not merge metadata when the schema is different:"
          + mergedMetadata.getSchema() + " != " + toMerge.getSchema());
    } else {
      for (Entry<String, String> entry : toMerge.getKeyValueMetaData().entrySet()) {
        final String value = mergedMetadata.getKeyValueMetaData().get(entry.getKey());
        if (value == null) {
          mergedMetadata.getKeyValueMetaData().put(entry.getKey(), entry.getValue());
        } else if (!value.equals(entry.getValue())) {
          throw new RuntimeException(
              "could not merge metadata: key "+entry.getKey()
              + " has conflicting values " + value + " and " + entry.getValue());
        }
      }
    }
    return mergedMetadata;
  }

}
