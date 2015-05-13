package org.apache.hadoop.hive.ql.io.parquet;

import com.google.common.base.Joiner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.io.ObjectArrayWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import parquet.hadoop.ParquetWriter;
import parquet.hadoop.api.WriteSupport;
import parquet.io.api.RecordConsumer;
import parquet.schema.MessageType;

public abstract class AbstractTestParquetDirect {

  public static FileSystem localFS = null;

  @BeforeClass
  public static void initializeFS() throws IOException {
    localFS = FileSystem.getLocal(new Configuration());
  }

  @Rule
  public final TemporaryFolder tempDir = new TemporaryFolder();


  public interface DirectWriter {
    public void write(RecordConsumer consumer);
  }

  public static class DirectWriteSupport extends WriteSupport<Void> {
    private RecordConsumer recordConsumer;
    private final MessageType type;
    private final DirectWriter writer;
    private final Map<String, String> metadata;

    private DirectWriteSupport(MessageType type, DirectWriter writer,
                               Map<String, String> metadata) {
      this.type = type;
      this.writer = writer;
      this.metadata = metadata;
    }

    @Override
    public WriteContext init(Configuration configuration) {
      return new WriteContext(type, metadata);
    }

    @Override
    public void prepareForWrite(RecordConsumer recordConsumer) {
      this.recordConsumer = recordConsumer;
    }

    @Override
    public void write(Void record) {
      writer.write(recordConsumer);
    }
  }

  public Path writeDirect(String name, MessageType type, DirectWriter writer)
      throws IOException {
    File temp = tempDir.newFile(name + ".parquet");
    temp.deleteOnExit();
    temp.delete();

    Path path = new Path(temp.getPath());

    ParquetWriter<Void> parquetWriter = new ParquetWriter<Void>(path,
        new DirectWriteSupport(type, writer, new HashMap<String, String>()));
    parquetWriter.write(null);
    parquetWriter.close();

    return path;
  }

  public static ObjectArrayWritable record(Object... fields) {
    return new ObjectArrayWritable(fields);
  }

  public static ObjectArrayWritable list(Object... elements) {
    // the ObjectInspector for array<?> and map<?, ?> expects an extra layer
    return new ObjectArrayWritable(new Object[] {
        new ObjectArrayWritable(elements)
    });
  }

  public static String toString(ObjectArrayWritable arrayWritable) {
    Object[] elements = arrayWritable.get();
    String[] strings = new String[elements.length];
    for (int i = 0; i < elements.length; i += 1) {
      if (elements[i] instanceof ObjectArrayWritable) {
        strings[i] = toString((ObjectArrayWritable) elements[i]);
      } else {
        strings[i] = String.valueOf(elements[i]);
      }
    }
    return Arrays.toString(strings);
  }

  public static void assertEquals(String message, ObjectArrayWritable expected,
                                  ObjectArrayWritable actual) {
    Assert.assertEquals(message, toString(expected), toString(actual));
  }

  public static List<ObjectArrayWritable> read(Path parquetFile) throws IOException {
    List<ObjectArrayWritable> records = new ArrayList<ObjectArrayWritable>();

    RecordReader<Void, ObjectArrayWritable> reader = new MapredParquetInputFormat().
        getRecordReader(new FileSplit(
                parquetFile, 0, fileLength(parquetFile), (String[]) null),
            new JobConf(), null);

    Void alwaysNull = reader.createKey();
    ObjectArrayWritable record = reader.createValue();
    while (reader.next(alwaysNull, record)) {
      records.add(record);
      record = reader.createValue(); // a new value so the last isn't clobbered
    }

    return records;
  }

  public static long fileLength(Path localFile) throws IOException {
    return localFS.getFileStatus(localFile).getLen();
  }

  private static final Joiner COMMA = Joiner.on(",");
  public void deserialize(Writable record, List<String> columnNames,
                          List<String> columnTypes) throws Exception {
    ParquetHiveSerDe serde = new ParquetHiveSerDe();
    Properties props = new Properties();
    props.setProperty(serdeConstants.LIST_COLUMNS, COMMA.join(columnNames));
    props.setProperty(serdeConstants.LIST_COLUMN_TYPES, COMMA.join(columnTypes));
    serde.initialize(null, props);
    serde.deserialize(record);
  }
}
