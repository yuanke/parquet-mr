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

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.junit.Test;

import parquet.column.Encoding;
import parquet.hadoop.api.ReadSupport;
import parquet.hadoop.metadata.BlockMetaData;
import parquet.hadoop.metadata.ColumnChunkMetaData;
import parquet.hadoop.metadata.ColumnPath;
import parquet.hadoop.metadata.CompressionCodecName;
import parquet.hadoop.metadata.FileMetaData;
import parquet.schema.MessageType;
import parquet.schema.MessageTypeParser;
import parquet.schema.PrimitiveType.PrimitiveTypeName;

public class TestInputFormat {

  @Test
  public void testBlocksToSplits() throws IOException, InterruptedException {
    List<BlockMetaData> blocks = new ArrayList<BlockMetaData>();
    for (int i = 0; i < 10; i++) {
      blocks.add(newBlock(i * 10));
    }
    BlockLocation[] hdfsBlocks = new BlockLocation[] {
        new BlockLocation(new String[0], new String[] { "foo0.datanode", "bar0.datanode"}, 0, 50),
        new BlockLocation(new String[0], new String[] { "foo1.datanode", "bar1.datanode"}, 50, 50)
    };
    FileStatus fileStatus = new FileStatus(100, false, 2, 50, 0, new Path("hdfs://foo.namenode:1234/bar"));
    MessageType schema = MessageTypeParser.parseMessageType("message doc { required binary foo; }");
    FileMetaData fileMetaData = new FileMetaData(schema, new HashMap<String, String>(), "parquet-mr");
    @SuppressWarnings("serial")
    List<ParquetInputSplit> splits = ParquetInputFormat.generateSplits(
        blocks, hdfsBlocks, fileStatus, fileMetaData, ReadSupport.class, schema.toString(), new HashMap<String, String>() {{put("specific", "foo");}});
    assertEquals(splits.toString().replaceAll("([{])", "$0\n").replaceAll("([}])", "\n$0"), 2, splits.size());
    for (int i = 0; i < splits.size(); i++) {
      ParquetInputSplit parquetInputSplit = splits.get(i);
      assertEquals(5, parquetInputSplit.getBlocks().size());
      assertEquals(2, parquetInputSplit.getLocations().length);
      assertEquals("[foo" + i + ".datanode, bar" + i + ".datanode]", Arrays.toString(parquetInputSplit.getLocations()));
      assertEquals(10, parquetInputSplit.getLength());
      assertEquals("foo", parquetInputSplit.getReadSupportMetadata().get("specific"));
    }
  }

  private BlockMetaData newBlock(long start) {
    BlockMetaData blockMetaData = new BlockMetaData();
    ColumnChunkMetaData column = ColumnChunkMetaData.get(
        ColumnPath.get("foo"), PrimitiveTypeName.BINARY, CompressionCodecName.GZIP, new HashSet<Encoding>(Arrays.asList(Encoding.PLAIN)),
        start, 0l, 0l, 2l, 0l);
    blockMetaData.addColumn(column);
    return blockMetaData;
  }
}
