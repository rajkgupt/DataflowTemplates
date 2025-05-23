/*
 * Copyright (C) 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.templates.common;

import static com.google.cloud.teleport.spanner.common.NameUtils.quoteIdentifier;
import static com.google.cloud.teleport.spanner.common.NameUtils.splitName;

import com.google.auto.value.AutoValue;
import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.BatchReadOnlyTransaction;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.PartitionOptions;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.Type;
import com.google.cloud.spanner.Type.Code;
import com.google.cloud.spanner.Type.StructField;
import com.google.cloud.teleport.metadata.TemplateParameter;
import com.google.cloud.teleport.spanner.spannerio.ReadOperation;
import com.google.cloud.teleport.spanner.spannerio.SpannerAccessor;
import com.google.cloud.teleport.spanner.spannerio.SpannerConfig;
import com.google.cloud.teleport.spanner.spannerio.Transaction;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import kotlin.Pair;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Transforms & DoFns & Options for SpannerIO. */
public class SpannerConverters {
  private static final Logger LOG = LoggerFactory.getLogger(SpannerConverters.class);

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  /** Options for exporting a Spanner table. */
  public interface SpannerReadOptions extends PipelineOptions {
    @TemplateParameter.Text(
        order = 1,
        groupName = "Source",
        regexes = {"^.+$"},
        description = "Spanner Table",
        helpText = "The Spanner table to read the data from.")
    ValueProvider<String> getSpannerTable();

    @SuppressWarnings("unused")
    void setSpannerTable(ValueProvider<String> table);

    @TemplateParameter.ProjectId(
        order = 2,
        groupName = "Source",
        description = "Read data from Cloud Spanner Project Id",
        helpText =
            "The ID of the Google Cloud project that contains the Spanner database to read data from.")
    ValueProvider<String> getSpannerProjectId();

    @SuppressWarnings("unused")
    void setSpannerProjectId(ValueProvider<String> spannerReadProjectId);

    @TemplateParameter.Text(
        order = 3,
        groupName = "Source",
        regexes = {".+"},
        description = "Read data from Cloud Spanner Instance",
        helpText = "The instance ID of the requested table.")
    ValueProvider<String> getSpannerInstanceId();

    @SuppressWarnings("unused")
    void setSpannerInstanceId(ValueProvider<String> spannerInstanceId);

    @TemplateParameter.Text(
        order = 4,
        groupName = "Source",
        regexes = {".+"},
        description = "Read data from Cloud Spanner Database ",
        helpText = "The database ID of the requested table.")
    ValueProvider<String> getSpannerDatabaseId();

    @SuppressWarnings("unused")
    void setSpannerDatabaseId(ValueProvider<String> spannerDatabaseId);

    @TemplateParameter.Text(
        order = 5,
        groupName = "Source",
        optional = true,
        description = "Cloud Spanner Endpoint to call",
        helpText = "The Cloud Spanner endpoint to call in the template. Only used for testing.",
        example = "https://batch-spanner.googleapis.com")
    @Default.String("https://batch-spanner.googleapis.com")
    ValueProvider<String> getSpannerHost();

    @SuppressWarnings("unused")
    void setSpannerHost(ValueProvider<String> value);

    @TemplateParameter.Text(
        order = 6,
        groupName = "Source",
        optional = true,
        regexes = {
          "^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):(([0-9]{2})(\\.[0-9]+)?)Z$"
        },
        description = "Snapshot time",
        helpText =
            "The timestamp that corresponds to the version of the Spanner database that you want to read from."
                + " The timestamp must be specified in the RFC 3339 (https://tools.ietf.org/html/rfc3339) UTC Zulu Time format."
                + " The timestamp must be in the past and"
                + " maximum timestamp staleness (https://cloud.google.com/spanner/docs/timestamp-bounds#maximum_timestamp_staleness) applies.",
        example = "1990-12-31T23:59:60Z")
    @Default.String(value = "")
    ValueProvider<String> getSpannerSnapshotTime();

    @SuppressWarnings("unused")
    void setSpannerSnapshotTime(ValueProvider<String> value);

    @TemplateParameter.Boolean(
        order = 7,
        groupName = "Source",
        optional = true,
        description = "Use independent compute resource (Spanner DataBoost).",
        helpText =
            "Set to `true` to use the compute resources of Spanner Data Boost to run the job with near-zero"
                + " impact on Spanner OLTP workflows. When true, requires the `spanner.databases.useDataBoost` Identity and"
                + " Access Management (IAM) permission. For more information, see"
                + " Data Boost overview (https://cloud.google.com/spanner/docs/databoost/databoost-overview).")
    @Default.Boolean(false)
    ValueProvider<Boolean> getDataBoostEnabled();

    void setDataBoostEnabled(ValueProvider<Boolean> value);
  }

  /** Factory for Export transform class. */
  public static class ExportTransformFactory {

    public static ExportTransform create(
        ValueProvider<String> table,
        SpannerConfig spannerConfig,
        ValueProvider<String> textWritePrefix,
        ValueProvider<String> timestamp,
        ValueProvider<String> columnsToExport,
        ValueProvider<Boolean> exportSchema) {
      return ExportTransform.builder()
          .table(table)
          .spannerConfig(spannerConfig)
          .textWritePrefix(textWritePrefix)
          .timestamp(timestamp)
          .columnsToExport(columnsToExport)
          .exportSchema(exportSchema)
          .build();
    }

    public static ExportTransform create(
        ValueProvider<String> table,
        SpannerConfig spannerConfig,
        ValueProvider<String> textWritePrefix,
        ValueProvider<String> timestamp) {
      return create(
          table,
          spannerConfig,
          textWritePrefix,
          timestamp,
          ValueProvider.StaticValueProvider.of(null),
          ValueProvider.StaticValueProvider.of(true));
    }
  }

  /** PTransform used to export the table. */
  @AutoValue
  abstract static class ExportTransform extends PTransform<PBegin, PCollection<ReadOperation>> {
    private static final Logger LOG = LoggerFactory.getLogger(ExportTransform.class);

    abstract ValueProvider<String> table();

    abstract ValueProvider<String> columnsToExport();

    abstract SpannerConfig spannerConfig();

    abstract ValueProvider<Boolean> exportSchema();

    abstract ValueProvider<String> textWritePrefix();

    abstract ValueProvider<String> timestamp();

    @Override
    public PCollection<ReadOperation> expand(PBegin begin) {
      // PTransform expand does not have access to template parameters but DoFn does.
      // Spanner parameter values are required to get the table schema information.
      return begin
          .apply("Pipeline start", Create.of(ImmutableList.of("")))
          .apply("ExportFn", ParDo.of(new ExportFn(timestamp())));
    }

    /** Builder for ExportTransform function. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder table(ValueProvider<String> table);

      public abstract Builder columnsToExport(ValueProvider<String> columnsToExport);

      public abstract Builder exportSchema(ValueProvider<Boolean> exportSchema);

      public abstract Builder spannerConfig(SpannerConfig spannerConfig);

      public abstract Builder textWritePrefix(ValueProvider<String> textWritePrefix);

      public abstract Builder timestamp(ValueProvider<String> timestamp);

      public abstract ExportTransform build();
    }

    public static Builder builder() {
      return new AutoValue_SpannerConverters_ExportTransform.Builder();
    }

    private SpannerAccessor spannerAccessor;
    private DatabaseClient databaseClient;

    // SpannerAccessor is not serializable, thus can't be passed as a mock so we need to pass
    // mocked database client directly instead. We can't generate stub of ExportTransform because
    // AutoValue generates a final class.
    // TODO make SpannerAccessor serializable
    DatabaseClient getDatabaseClient(SpannerConfig spannerConfig) {
      if (databaseClient == null) {
        this.spannerAccessor = SpannerAccessor.getOrCreate(spannerConfig);
        return this.spannerAccessor.getDatabaseClient();
      } else {
        return this.databaseClient;
      }
    }

    public void setDatabaseClient(DatabaseClient databaseClient) {
      this.databaseClient = databaseClient;
    }

    void closeSpannerAccessor() {
      if (spannerAccessor != null) {
        this.spannerAccessor.close();
      }
    }

    /** Function used to export the table. */
    public class ExportFn extends DoFn<String, ReadOperation> {

      static final String SCHEMA_SUFFIX = "schema";
      // The number of read partitions have to be capped so that in case the Partition token is
      // large
      // (which can happen with a table with a lot of columns), the PartitionResponse size is
      // bounded.
      private final int maxPartitions = 1000;

      private final ValueProvider<String> timestamp;

      public ExportFn(ValueProvider<String> timestamp) {
        this.timestamp = timestamp;
      }

      @ProcessElement
      @SuppressWarnings("unused")
      public void processElement(ProcessContext processContext) {
        // Save schema to GCS so it can be saved along with the exported file.
        LOG.info("Creating database client for schema read");

        Dialect dialect;
        LinkedHashMap<String, String> columns;
        try {
          DatabaseClient databaseClient = getDatabaseClient(spannerConfig());
          String timestampString = this.timestamp.get();
          TimestampBound tsbound = getTimestampBound(timestampString);
          LOG.info("Reading dialect information");
          dialect = databaseClient.getDialect();

          try (ReadOnlyTransaction context = databaseClient.readOnlyTransaction(tsbound)) {
            LOG.info("Reading schema information");
            columns = getAllColumns(context, table().get(), dialect);
            String columnJson = SpannerConverters.GSON.toJson(columns);

            if (BooleanUtils.isTrue(exportSchema().get())) {
              LOG.info("Saving schema information");
              saveSchema(columnJson, textWritePrefix().get() + SCHEMA_SUFFIX);
            }
          }
        } finally {
          closeSpannerAccessor();
        }

        PartitionOptions partitionOptions =
            PartitionOptions.newBuilder().setMaxPartitions(maxPartitions).build();

        String columnsListAsString;

        if (StringUtils.isNotBlank(columnsToExport().get())) {
          LOG.info("Exporting selected columns passed by user: {}", columnsToExport().get());
          columnsListAsString = prepareColumnExpression(columnsToExport().get());
        } else {
          columnsListAsString =
              columns.entrySet().stream()
                  .map(x -> createColumnExpression(x.getKey(), x.getValue(), dialect))
                  .collect(Collectors.joining(","));
        }

        ReadOperation read;

        switch (dialect) {
          case GOOGLE_STANDARD_SQL:
            read =
                ReadOperation.create()
                    .withQuery(
                        String.format(
                            "SELECT %s FROM %s",
                            columnsListAsString, quoteIdentifier(table().get(), dialect)))
                    .withPartitionOptions(partitionOptions);
            break;
          case POSTGRESQL:
            read =
                ReadOperation.create()
                    .withQuery(
                        String.format(
                            "SELECT %s FROM %s;",
                            columnsListAsString, quoteIdentifier(table().get(), dialect)))
                    .withPartitionOptions(partitionOptions);
            break;
          default:
            throw new IllegalArgumentException(String.format("Unrecognized dialect: %s", dialect));
        }
        processContext.output(read);
      }

      private String createColumnExpression(String columnName, String columnType, Dialect dialect) {
        if (dialect == Dialect.POSTGRESQL) {
          // TODO(b/394493438): Remove casting once google-cloud-spanner supports UUID type
          if (columnType.equals("uuid")) {
            return String.format("\"%s\"::text AS \"%s\"", columnName, columnName);
          }
          if (columnType.equals("uuid[]")) {
            return String.format(
                "CASE WHEN \"%s\" IS NULL THEN NULL ELSE "
                    + "ARRAY(SELECT e::text FROM UNNEST(\"%s\") AS e) END AS \"%s\"",
                columnName, columnName, columnName);
          }
          return "\"" + columnName + "\"";
        }
        if (columnType.equals("NUMERIC")) {
          return "CAST(`" + columnName + "` AS STRING) AS " + columnName;
        }
        if (columnType.equals("JSON")) {
          return "`" + columnName + "`";
        }
        // TODO(b/394493438): Remove casting once google-cloud-spanner supports UUID type
        if (columnType.equals("UUID")) {
          return String.format("CAST(`%s` AS STRING) AS %s", columnName, columnName);
        }
        if (columnType.equals("ARRAY<UUID>")) {
          return String.format(
              "CASE WHEN `%s` IS NULL THEN NULL ELSE "
                  + "ARRAY(SELECT CAST(e AS STRING) FROM UNNEST(%s) AS e) END AS %s",
              columnName, columnName, columnName);
        }
        if (columnType.equals("ARRAY<NUMERIC>")) {
          return "(SELECT ARRAY_AGG(CAST(num AS STRING)) FROM UNNEST(`"
              + columnName
              + "`) AS num) AS "
              + columnName;
        }
        if (columnType.equals("ARRAY<JSON>")) {
          return "(SELECT ARRAY_AGG(TO_JSON_STRING(element)) FROM UNNEST(`"
              + columnName
              + "`) AS element) AS "
              + columnName;
        }
        return "`" + columnName + "`";
      }

      private void saveSchema(String content, String schemaPath) {
        LOG.info("Schema: " + content);

        try {
          WritableByteChannel chan =
              FileSystems.create(FileSystems.matchNewResource(schemaPath, false), "text/plain");
          try (OutputStream stream = Channels.newOutputStream(chan)) {
            stream.write(content.getBytes());
          }
        } catch (IOException e) {
          throw new RuntimeException("Failed to write schema", e);
        }
      }
    }

    /** Function to get all column names from the table. */
    private LinkedHashMap<String, String> getAllColumns(
        ReadContext context, String tableName, Dialect dialect) {
      LinkedHashMap<String, String> columns = Maps.newLinkedHashMap();
      Pair<String, String> paths = splitName(tableName, dialect);
      Statement statement;
      ResultSet resultSet;
      switch (dialect) {
        case GOOGLE_STANDARD_SQL:
          String googleSQL =
              "SELECT COLUMN_NAME, SPANNER_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                  + "WHERE TABLE_NAME=@table_name AND TABLE_SCHEMA=@schema_name "
                  + "AND IS_GENERATED = 'NEVER' ORDER BY ORDINAL_POSITION";
          statement =
              Statement.newBuilder(googleSQL)
                  .bind("table_name")
                  .to(paths.getSecond())
                  .bind("schema_name")
                  .to(paths.getFirst())
                  .build();
          break;
        case POSTGRESQL:
          String postgreSQL =
              "SELECT COLUMN_NAME, SPANNER_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=$1"
                  + " AND TABLE_SCHEMA=$2"
                  + " AND IS_GENERATED = 'NEVER' ORDER BY ORDINAL_POSITION;";
          statement =
              Statement.newBuilder(postgreSQL)
                  .bind("p1")
                  .to(paths.getSecond())
                  .bind("p2")
                  .to(paths.getFirst())
                  .build();
          break;
        default:
          throw new IllegalArgumentException(String.format("Unrecognized dialect: %s", dialect));
      }
      LOG.info("Got schema information. Reading columns:" + statement.toString());
      resultSet = context.executeQuery(statement);
      while (resultSet.next()) {
        Struct currentRow = resultSet.getCurrentRowAsStruct();
        columns.put(currentRow.getString(0), currentRow.getString(1));
      }
      LOG.info("Columns:" + columns.toString());
      return columns;
    }
  }

  /**
   * Generate a column expression for use in a query based on user-supplied column and alias values.
   *
   * @param columnsToExport User-provided data in the format of "column1: alias1, column2, column3:
   *     alias3".
   * @return A prepared column expression suitable for use in a Data Query Language (DQL) statement.
   */
  @VisibleForTesting
  static String prepareColumnExpression(String columnsToExport) {
    String[] columnsList = columnsToExport.split(",");
    StringBuilder columnExpressionBuilder = new StringBuilder();

    for (String columnWithAlias : columnsList) {
      if (StringUtils.isBlank(columnWithAlias)) {
        throw new IllegalArgumentException(
            String.format(
                "Please provide a valid comma-separated list of columns to be extracted. The received input is not valid: %s",
                columnsToExport));
      }
      // Split around ":" to check if alias is present
      String[] columnMap = columnWithAlias.trim().split(":");
      columnExpressionBuilder.append(columnMap[0].trim());
      if (columnMap.length > 1) {
        columnExpressionBuilder.append(" AS ");
        columnExpressionBuilder.append(columnMap[1].trim());
      }
      columnExpressionBuilder.append(",");
    }

    // Removing the last character which is ','
    if (columnExpressionBuilder.length() > 0) {
      columnExpressionBuilder.deleteCharAt(columnExpressionBuilder.length() - 1);
    }

    return columnExpressionBuilder.toString();
  }

  /** Interface for all the Struct Printers. */
  interface StructPrinter {
    String print(Struct struct);
  }

  /**
   * Struct printer for converting a Spanner Struct to CSV. See {@link this#parseArrayValue(Struct,
   * String)} for data type conversions. It uses a standard comma separated format as for RFC4180
   * but allowing empty lines.
   */
  public static class StructCsvPrinter implements StructPrinter {

    /**
     * Prints Struct as a CSV String.
     *
     * @param struct Spanner Struct.
     * @return Spanner Struct encoded as a CSV String.
     */
    @Override
    public String print(Struct struct) {
      StringWriter stringWriter = new StringWriter();
      try {
        CSVPrinter printer =
            new CSVPrinter(
                stringWriter,
                CSVFormat.DEFAULT.withRecordSeparator("").withQuoteMode(QuoteMode.ALL_NON_NULL));
        LinkedHashMap<String, BiFunction<Struct, String, String>> parsers = Maps.newLinkedHashMap();
        parsers.putAll(mapColumnParsers(struct.getType().getStructFields()));
        List<String> values = parseResultSet(struct, parsers);
        printer.printRecord(values);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return stringWriter.toString();
    }

    /**
     * Parse results given by table read. The function returns a list of strings, where each string
     * represents a single column. The list constitutes the entire result set (columns in all of the
     * records).
     */
    private static List<String> parseResultSet(
        Struct struct, LinkedHashMap<String, BiFunction<Struct, String, String>> parsers) {
      List<String> result = Lists.newArrayList();

      for (String columnName : parsers.keySet()) {
        if (!parsers.containsKey(columnName)) {
          throw new RuntimeException("No parser for column: " + columnName);
        }
        result.add(parsers.get(columnName).apply(struct, columnName));
      }
      return result;
    }
  }

  /** Struct printer for converting a Spanner Struct to JSON. */
  public static class StructJSONPrinter implements StructPrinter {
    private StructValidator structValidator = null;

    public StructJSONPrinter(StructValidator structValidator) {
      this.structValidator = structValidator;
    }

    public StructJSONPrinter() {}

    /**
     * To get string in json format from Struct.
     *
     * @param struct Spanner Struct.
     * @return Spanner Struct encoded as a JSON String.
     */
    @Override
    public String print(Struct struct) {
      if (structValidator != null) {
        structValidator.validate(struct);
      }

      StringWriter stringWriter = new StringWriter();
      try {
        JsonWriter jsonWriter = new JsonWriter(stringWriter);
        LinkedHashMap<String, BiFunction<Struct, String, String>> parsers = Maps.newLinkedHashMap();
        parsers.putAll(mapColumnParsers(struct.getType().getStructFields()));
        parseResultSet(jsonWriter, struct, parsers);
      } catch (IOException e) {
        throw new RuntimeException("Error parsing the Spanner struct to JSON", e);
      }
      return stringWriter.toString();
    }

    /**
     * To parse Struct using different parsers for different type and process it using JSONWriter.
     *
     * @param jsonWriter JSON Writer
     * @param struct Data from Spanner
     * @param parsers Map from Column Name to their parser
     * @throws IOException
     */
    private static void parseResultSet(
        JsonWriter jsonWriter,
        Struct struct,
        LinkedHashMap<String, BiFunction<Struct, String, String>> parsers)
        throws IOException {
      // Start the JSON object
      jsonWriter.beginObject();

      for (String columnName : parsers.keySet()) {
        String columnValue = parsers.get(columnName).apply(struct, columnName);

        // If null value then ignoring it and not exporting to JSON file
        if (columnValue == null) {
          continue;
        }

        if (Arrays.asList(Code.JSON, Code.PG_JSONB)
            .contains(struct.getColumnType(columnName).getCode())) {
          // If the column is of type JSON or PG_JSON
          jsonWriter.name(columnName).jsonValue(columnValue);
        } else if (struct.getColumnType(columnName).getCode() == Code.ARRAY) {
          // If the column is of type ARRAY
          List<? extends Object> values = parseArrayValueAsObjectList(struct, columnName);
          jsonWriter.name(columnName);
          jsonWriter.beginArray();
          for (Object value : values) {
            jsonWriter.value(value != null ? value.toString() : null);
          }
          jsonWriter.endArray();
        } else {
          jsonWriter.name(columnName).value(columnValue);
        }
      }
      jsonWriter.endObject();
    }
  }

  /**
   * To parse array value as list of derived objects.
   *
   * @param currentRow Struct
   * @param columnName Column Name
   * @return List of generic objects
   */
  private static List<? extends Object> parseArrayValueAsObjectList(
      Struct currentRow, String columnName) {
    Code code = currentRow.getColumnType(columnName).getArrayElementType().getCode();
    switch (code) {
      case BOOL:
        return currentRow.getBooleanList(columnName);
      case INT64:
      case ENUM:
        return currentRow.getLongList(columnName);
      case FLOAT32:
        return currentRow.getFloatList(columnName);
      case FLOAT64:
        return currentRow.getDoubleList(columnName);
      case STRING:
      case PG_NUMERIC:
      case JSON:
        return currentRow.getStringList(columnName);
      case PG_JSONB:
        return currentRow.getPgJsonbList(columnName);
      case BYTES:
      case PROTO:
        return currentRow.getBytesList(columnName).stream()
            .map(byteArray -> Base64.getEncoder().encodeToString(byteArray.toByteArray()))
            .collect(Collectors.toList());
      case DATE:
        return currentRow.getDateList(columnName).stream()
            .map(Date::toString)
            .collect(Collectors.toList());
      case TIMESTAMP:
        return currentRow.getTimestampList(columnName).stream()
            .map(Timestamp::toString)
            .collect(Collectors.toList());
      default:
        throw new RuntimeException("Unsupported type: " + code);
    }
  }

  /** Function to map columns to their corresponding parsing function. */
  private static LinkedHashMap<String, BiFunction<Struct, String, String>> mapColumnParsers(
      List<StructField> fields) {
    LinkedHashMap<String, BiFunction<Struct, String, String>> columnParsers =
        Maps.newLinkedHashMap();
    for (StructField field : fields) {
      columnParsers.put(field.getName(), getColumnParser(field.getType().getCode()));
    }
    return columnParsers;
  }

  private static BiFunction<Struct, String, String> nullSafeColumnParser(
      BiFunction<Struct, String, String> columnParser) {
    return (currentRow, columnName) ->
        currentRow.isNull(columnName) ? null : columnParser.apply(currentRow, columnName);
  }

  /**
   * Function with a series of switch cases to determine the parsing function for a specific column
   * type:
   *
   * <p>- Primitive types such as Boolean, Long, Int, and String are converted using toString parser
   * for primitive types. - Date and Timestamp use toString method, for example 2018-03-26 and
   * 1970-01-01T00:00:00Z - Byte arrays use base64 encoding, so for example "test" transforms to
   * "dGVzdA=="
   */
  private static BiFunction<Struct, String, String> getColumnParser(Type.Code columnType) {
    switch (columnType) {
      case BOOL:
        return nullSafeColumnParser(
            (currentRow, columnName) -> Boolean.toString(currentRow.getBoolean(columnName)));
      case INT64:
      case ENUM:
        return nullSafeColumnParser(
            (currentRow, columnName) -> Long.toString(currentRow.getLong(columnName)));
      case FLOAT32:
        return nullSafeColumnParser(
            ((currentRow, columnName) -> Float.toString(currentRow.getFloat(columnName))));
      case FLOAT64:
        return nullSafeColumnParser(
            ((currentRow, columnName) -> Double.toString(currentRow.getDouble(columnName))));
      case STRING:
      case PG_NUMERIC:
        return nullSafeColumnParser(Struct::getString);
      case JSON:
        return nullSafeColumnParser(Struct::getJson);
      case PG_JSONB:
        return nullSafeColumnParser(Struct::getPgJsonb);
      case BYTES:
      case PROTO:
        return nullSafeColumnParser(
            (currentRow, columnName) ->
                Base64.getEncoder().encodeToString(currentRow.getBytes(columnName).toByteArray()));
      case DATE:
        return nullSafeColumnParser(
            (currentRow, columnName) -> currentRow.getDate(columnName).toString());
      case TIMESTAMP:
        return nullSafeColumnParser(
            (currentRow, columnName) -> currentRow.getTimestamp(columnName).toString());
      case ARRAY:
        return nullSafeColumnParser(SpannerConverters::parseArrayValue);
      default:
        throw new RuntimeException("Unsupported type: " + columnType);
    }
  }

  /**
   * Helper to parse array types. Arrays are converted to JSON representation. Array inner types use
   * the same serialization algorithm as in {@link this#getColumnParser(Code)}, for example [test,
   * foo] is transformed to [""test"", ""foo""]
   */
  private static String parseArrayValue(Struct currentRow, String columnName) {
    Code code = currentRow.getColumnType(columnName).getArrayElementType().getCode();
    switch (code) {
      case BOOL:
        return GSON.toJson(currentRow.getBooleanArray(columnName));
      case INT64:
      case ENUM:
        return GSON.toJson(currentRow.getLongArray(columnName));
      case FLOAT32:
        return GSON.toJson(currentRow.getFloatArray(columnName));
      case FLOAT64:
        return GSON.toJson(currentRow.getDoubleArray(columnName));
      case STRING:
      case PG_NUMERIC:
        return GSON.toJson(currentRow.getStringList(columnName));
      case BYTES:
      case PROTO:
        return GSON.toJson(
            currentRow.getBytesList(columnName).stream()
                .map(byteArray -> Base64.getEncoder().encodeToString(byteArray.toByteArray()))
                .collect(Collectors.toList()));
      case DATE:
        return GSON.toJson(
            currentRow.getDateList(columnName).stream()
                .map(Date::toString)
                .collect(Collectors.toList()));
      case TIMESTAMP:
        return GSON.toJson(
            currentRow.getTimestampList(columnName).stream()
                .map(Timestamp::toString)
                .collect(Collectors.toList()));
      default:
        throw new RuntimeException("Unsupported type: " + code);
    }
  }

  /**
   * A DoFn that creates a transaction for read that honors the timestamp ValueProvider parameter.
   */
  public static class CreateTransactionFnWithTimestamp extends DoFn<Object, Transaction> {
    private final SpannerConfig config;
    private final ValueProvider<String> spannerSnapshotTime;

    public CreateTransactionFnWithTimestamp(
        SpannerConfig config, ValueProvider<String> spannerSnapshotTime) {
      this.config = config;
      this.spannerSnapshotTime = spannerSnapshotTime;
    }

    private transient SpannerAccessor spannerAccessor;

    @DoFn.Setup
    public void setup() throws Exception {
      spannerAccessor = SpannerAccessor.getOrCreate(config);
    }

    @Teardown
    public void teardown() throws Exception {
      spannerAccessor.close();
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      String timestamp = this.spannerSnapshotTime.get();
      TimestampBound tsbound = getTimestampBound(timestamp);
      BatchReadOnlyTransaction tx =
          spannerAccessor.getBatchClient().batchReadOnlyTransaction(tsbound);
      c.output(Transaction.create(tx.getBatchTransactionId()));
    }
  }

  /**
   * Given a timestamp in the form of a ValueProvider, it returns that timestamp converted to a
   * TimestampBound.
   */
  static TimestampBound getTimestampBound(String timestamp) {
    if ("".equals(timestamp)) {
      /* If no timestamp is specified, read latest data */
      return TimestampBound.strong();
    } else {
      /* Else try to read data in the timestamp specified. */
      com.google.cloud.Timestamp tsVal;
      try {
        tsVal = com.google.cloud.Timestamp.parseTimestamp(timestamp);
      } catch (Exception e) {
        throw new IllegalStateException("Invalid timestamp specified " + timestamp);
      }

      /*
       * If timestamp specified is in the future, spanner read will wait
       * till the time has passed. Abort the job and complain early.
       */
      if (tsVal.compareTo(com.google.cloud.Timestamp.now()) > 0) {
        throw new IllegalStateException("Timestamp specified is in future " + timestamp);
      }

      /*
       * Export jobs with Timestamps which are older than
       * maximum staleness time (one hour) fail with the FAILED_PRECONDITION
       * error - https://cloud.google.com/spanner/docs/timestamp-bounds
       * Hence we do not handle the case.
       */

      return TimestampBound.ofReadTimestamp(tsVal);
    }
  }

  /** Interface to validate Struct. If validation fails then throws RuntimeException */
  interface StructValidator {
    void validate(Struct struct);
  }

  /**
   * To validate struct and check whether data can be exported in the format supported by Vertex AI
   * Vector Search Index.
   */
  public static class VectorSearchStructValidator implements StructValidator {
    private static final String ID = "id";
    private static final String EMBEDDING = "embedding";
    private static final String RESTRICTS = "restricts";
    private static final String CROWDING_TAG = "crowding_tag";

    Map<String, List<Type>> fieldExpectedTypesMap =
        Map.of(
            EMBEDDING, List.of(Type.array(Type.float64())),
            RESTRICTS, List.of(Type.json(), Type.pgJsonb()),
            CROWDING_TAG, List.of(Type.string()));

    @Override
    public void validate(Struct struct) {
      // Not Null
      validateNotNull(struct, ID);
      validateNotNull(struct, EMBEDDING);

      // Type Check
      validateType(struct, EMBEDDING, fieldExpectedTypesMap.get(EMBEDDING));
      validateTypeIfExists(struct, RESTRICTS, fieldExpectedTypesMap.get(RESTRICTS));
      validateTypeIfExists(struct, CROWDING_TAG, fieldExpectedTypesMap.get(CROWDING_TAG));
    }

    /** To validate type of the column in struct. */
    private void validateType(Struct struct, String columnName, List<Type> expectedTypes) {
      if (!expectedTypes.contains(struct.getColumnType(columnName))) {
        throw new RuntimeException(
            String.format(
                "Expected %s column to be one of the following type: %s but received %s",
                columnName, expectedTypes, struct.getColumnType(columnName)));
      }
    }

    /** To validate type of the column if it exists in struct. */
    private void validateTypeIfExists(Struct struct, String columnName, List<Type> expectedTypes) {
      boolean columnExists = true;
      try {
        struct.getColumnType(columnName);
      } catch (IllegalArgumentException e) {
        if (e.getMessage().startsWith("Field not found: ")) {
          columnExists = false;
        }
      }

      if (!columnExists) {
        // validating type if it exists
        return;
      }

      validateType(struct, columnName, expectedTypes);
    }

    /** To validate whether the required columns are not null. */
    private void validateNotNull(Struct struct, String columnName) {
      if (struct.isNull(columnName)) {
        throw new RuntimeException(String.format("Expected %s column to be not null", columnName));
      }
    }
  }
}
