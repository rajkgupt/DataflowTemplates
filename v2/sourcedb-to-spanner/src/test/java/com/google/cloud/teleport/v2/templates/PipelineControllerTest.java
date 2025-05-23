/*
 * Copyright (C) 2024 Google LLC
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
package com.google.cloud.teleport.v2.templates;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.teleport.v2.options.SourceDbToSpannerOptions;
import com.google.cloud.teleport.v2.source.reader.io.jdbc.iowrapper.JdbcIoWrapper;
import com.google.cloud.teleport.v2.source.reader.io.jdbc.iowrapper.config.JdbcIOWrapperConfig;
import com.google.cloud.teleport.v2.source.reader.io.jdbc.iowrapper.config.SQLDialect;
import com.google.cloud.teleport.v2.spanner.ddl.Ddl;
import com.google.cloud.teleport.v2.spanner.migrations.schema.ISchemaMapper;
import com.google.cloud.teleport.v2.spanner.migrations.schema.IdentityMapper;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SchemaFileOverridesBasedMapper;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SchemaStringOverridesBasedMapper;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SessionBasedMapper;
import com.google.cloud.teleport.v2.spanner.migrations.shard.Shard;
import com.google.cloud.teleport.v2.templates.PipelineController.ShardedJdbcDbConfigContainer;
import com.google.cloud.teleport.v2.templates.PipelineController.SingleInstanceJdbcDbConfigContainer;
import com.google.common.io.Resources;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.Wait;
import org.apache.beam.sdk.values.PCollection;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

@RunWith(MockitoJUnitRunner.class)
public class PipelineControllerTest {

  @Rule public final transient TestPipeline pipeline = TestPipeline.create();
  @Rule public final transient TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Ddl spannerDdl;
  private Ddl shardedDdl;
  private Path schemaOverridesFile;

  private MockedStatic<JdbcIoWrapper> mockedStaticJdbcIoWrapper;
  @Mock private JdbcIoWrapper mockJdbcIoWrapper;

  @Before
  public void setup() throws IOException {
    mockedStaticJdbcIoWrapper = Mockito.mockStatic(JdbcIoWrapper.class);
    spannerDdl =
        Ddl.builder()
            .createTable("new_cart")
            .column("new_quantity")
            .int64()
            .notNull()
            .endColumn()
            .column("new_user_id")
            .string()
            .size(10)
            .endColumn()
            .primaryKey()
            .asc("new_user_id")
            .asc("new_quantity")
            .end()
            .endTable()
            .createTable("new_people")
            .column("synth_id")
            .int64()
            .notNull()
            .endColumn()
            .column("new_name")
            .string()
            .size(10)
            .endColumn()
            .primaryKey()
            .asc("synth_id")
            .end()
            .endTable()
            .build();

    shardedDdl =
        Ddl.builder()
            .createTable("new_cart")
            .column("new_quantity")
            .int64()
            .notNull()
            .endColumn()
            .column("new_product_id")
            .string()
            .size(20)
            .endColumn()
            .column("new_user_id")
            .string()
            .size(20)
            .endColumn()
            .primaryKey()
            .asc("new_user_id")
            .asc("new_product_id")
            .end()
            .endTable()
            .createTable("new_people")
            .column("migration_shard_id")
            .string()
            .size(20)
            .endColumn()
            .column("new_name")
            .string()
            .size(20)
            .endColumn()
            .primaryKey()
            .asc("migration_shard_id")
            .asc("new_name")
            .end()
            .endTable()
            .build();

    // Create a dummy schema overrides file for tests that need it
    schemaOverridesFile = temporaryFolder.newFile("schema_overrides.json").toPath();
    String overridesJsonContent =
        "{\n"
            + "  \"renamedTables\": {\n"
            + "    \"source_table\": \"spanner_table\"\n"
            + "  }\n"
            + "}";
    try (BufferedWriter writer = Files.newBufferedWriter(schemaOverridesFile)) {
      writer.write(overridesJsonContent);
      writer.flush();
    }
  }

  @Test
  public void createIdentitySchemaMapper() {
    SourceDbToSpannerOptions mockOptions = createOptionsHelper(null, null, null, null, null);
    ISchemaMapper schemaMapper = PipelineController.getSchemaMapper(mockOptions, spannerDdl);
    assertTrue(schemaMapper instanceof IdentityMapper);
  }

  @Test
  public void createSessionSchemaMapper() {
    String sessionFilePath =
        Paths.get(Resources.getResource("session-file-with-dropped-column.json").getPath())
            .toString();
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(sessionFilePath, null, null, null, null);
    ISchemaMapper schemaMapper = PipelineController.getSchemaMapper(mockOptions, spannerDdl);
    assertTrue(schemaMapper instanceof SessionBasedMapper);
  }

  @Test
  public void createSchemaFileOverridesMapper() {
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(null, schemaOverridesFile.toString(), null, null, null);
    ISchemaMapper schemaMapper = PipelineController.getSchemaMapper(mockOptions, spannerDdl);
    assertTrue(schemaMapper instanceof SchemaFileOverridesBasedMapper);
  }

  @Test
  public void createStringOverridesMapper_tableOnly() {
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(null, null, "[{src,dest}]", null, null);
    ISchemaMapper schemaMapper = PipelineController.getSchemaMapper(mockOptions, spannerDdl);
    assertTrue(schemaMapper instanceof SchemaStringOverridesBasedMapper);
  }

  @Test
  public void createStringOverridesMapper_columnOnly() {
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(null, null, null, "[{src.col,src.spCol}]", null);
    ISchemaMapper schemaMapper = PipelineController.getSchemaMapper(mockOptions, spannerDdl);
    assertTrue(schemaMapper instanceof SchemaStringOverridesBasedMapper);
  }

  @Test
  public void createStringOverridesMapper_both() {
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(null, null, "[{s,d}]", "[{s.c,s.sc}]", null);
    ISchemaMapper schemaMapper = PipelineController.getSchemaMapper(mockOptions, spannerDdl);
    assertTrue(schemaMapper instanceof SchemaStringOverridesBasedMapper);
  }

  @Test
  public void createSchemaMapper_multipleOverrides_sessionAndFile_throwsException() {
    String sessionFilePath =
        Paths.get(Resources.getResource("session-file-with-dropped-column.json").getPath())
            .toString();
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(sessionFilePath, schemaOverridesFile.toString(), null, null, null);
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> PipelineController.getSchemaMapper(mockOptions, spannerDdl));
    assertTrue(
        exception.getMessage().contains("Only one type of schema override can be specified"));
  }

  @Test
  public void createSchemaMapper_multipleOverrides_sessionAndString_throwsException() {
    String sessionFilePath =
        Paths.get(Resources.getResource("session-file-with-dropped-column.json").getPath())
            .toString();
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(sessionFilePath, null, "[{s,d}]", null, null);
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> PipelineController.getSchemaMapper(mockOptions, spannerDdl));
    assertTrue(
        exception.getMessage().contains("Only one type of schema override can be specified"));
  }

  @Test
  public void createSchemaMapper_multipleOverrides_fileAndString_throwsException() {
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(null, schemaOverridesFile.toString(), "[{s,d}]", null, null);
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> PipelineController.getSchemaMapper(mockOptions, spannerDdl));
    assertTrue(
        exception.getMessage().contains("Only one type of schema override can be specified"));
  }

  @Test
  public void createSchemaMapper_multipleOverrides_all_throwsException() {
    String sessionFilePath =
        Paths.get(Resources.getResource("session-file-with-dropped-column.json").getPath())
            .toString();
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper(
            sessionFilePath, schemaOverridesFile.toString(), "[{s,d}]", "[{s.c,s.sc}]", null);
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> PipelineController.getSchemaMapper(mockOptions, spannerDdl));
    assertTrue(
        exception.getMessage().contains("Only one type of schema override can be specified"));
  }

  @Test(expected = Exception.class)
  public void createInvalidSchemaMapper_withException() {
    SourceDbToSpannerOptions mockOptions =
        createOptionsHelper("invalid-file", null, null, null, "");
    PipelineController.getSchemaMapper(mockOptions, spannerDdl);
  }

  @Test
  public void tableToShardIdColumnNonSharded() {
    String sessionFilePath =
        Paths.get(Resources.getResource("session-file-with-dropped-column.json").getPath())
            .toString();
    ISchemaMapper schemaMapper = new SessionBasedMapper(sessionFilePath, spannerDdl);
    Map<String, String> mapAllTables =
        PipelineController.getSrcTableToShardIdColumnMap(
            schemaMapper, "", List.of("new_cart", "new_people"));
    assertThat(mapAllTables.isEmpty());
  }

  @Test
  public void tableToShardIdColumnSharded() {
    String shardedSessionFilePath =
        Paths.get(Resources.getResource("session-file-sharded.json").getPath()).toString();
    ISchemaMapper schemaMapper = new SessionBasedMapper(shardedSessionFilePath, shardedDdl);
    Map<String, String> mapAllTables =
        PipelineController.getSrcTableToShardIdColumnMap(
            schemaMapper, "", List.of("new_cart", "new_people"));
    assertEquals(1, mapAllTables.size());
    assertEquals("migration_shard_id", mapAllTables.get("people"));
  }

  @Test(expected = NoSuchElementException.class)
  public void tableToShardIdColumnInvalidTable() {
    String sessionFilePath =
        Paths.get(Resources.getResource("session-file-with-dropped-column.json").getPath())
            .toString();
    ISchemaMapper schemaMapper = new SessionBasedMapper(sessionFilePath, spannerDdl);
    PipelineController.getSrcTableToShardIdColumnMap(
        schemaMapper, "", List.of("cart")); // Only accepts spanner table names
  }

  private SourceDbToSpannerOptions createOptionsHelper(
      String sessionFile,
      String schemaOverridesFile,
      String tableOverrides,
      String columnOverrides,
      String tables) {
    SourceDbToSpannerOptions mockOptions =
        mock(
            SourceDbToSpannerOptions.class,
            Mockito.withSettings().serializable().strictness(Strictness.LENIENT));
    when(mockOptions.getSessionFilePath()).thenReturn(sessionFile == null ? "" : sessionFile);
    when(mockOptions.getSchemaOverridesFilePath())
        .thenReturn(schemaOverridesFile == null ? "" : schemaOverridesFile);
    when(mockOptions.getTableOverrides()).thenReturn(tableOverrides == null ? "" : tableOverrides);
    when(mockOptions.getColumnOverrides())
        .thenReturn(columnOverrides == null ? "" : columnOverrides);
    when(mockOptions.getTables()).thenReturn(tables == null ? "" : tables);
    return mockOptions;
  }

  @Test
  public void singleDbConfigContainerWithUrlTest() {
    // Most of this is copied from OptionsToConfigBuilderTest.
    // Check if it is possible to re-use
    final String testDriverClassName = "org.apache.derby.jdbc.EmbeddedDriver";
    final String testUrl = "jdbc:mysql://localhost:3306/testDB";
    final String testUser = "user";
    final String testPassword = "password";
    SourceDbToSpannerOptions sourceDbToSpannerOptions =
        PipelineOptionsFactory.as(SourceDbToSpannerOptions.class);
    sourceDbToSpannerOptions.setSourceDbDialect(SQLDialect.MYSQL.name());
    sourceDbToSpannerOptions.setSourceConfigURL(testUrl);
    sourceDbToSpannerOptions.setJdbcDriverClassName(testDriverClassName);
    sourceDbToSpannerOptions.setMaxConnections(150);
    sourceDbToSpannerOptions.setNumPartitions(4000);
    sourceDbToSpannerOptions.setUsername(testUser);
    sourceDbToSpannerOptions.setPassword(testPassword);
    sourceDbToSpannerOptions.setTables("table1,table2");
    String sessionFilePath =
        Paths.get(Resources.getResource("session-file-with-dropped-column.json").getPath())
            .toString();
    sourceDbToSpannerOptions.setSessionFilePath(sessionFilePath); // Ensure session file is set
    ISchemaMapper schemaMapper =
        PipelineController.getSchemaMapper(sourceDbToSpannerOptions, spannerDdl);
    PCollection<Integer> dummyPCollection = pipeline.apply(Create.of(1));
    pipeline.run();
    SingleInstanceJdbcDbConfigContainer dbConfigContainer =
        new SingleInstanceJdbcDbConfigContainer(sourceDbToSpannerOptions);
    JdbcIOWrapperConfig config =
        dbConfigContainer.getJDBCIOWrapperConfig(
            List.of("table1", "table2"), Wait.on(dummyPCollection));
    assertThat(config.jdbcDriverClassName()).isEqualTo(testDriverClassName);
    assertThat(config.sourceDbURL())
        .isEqualTo(testUrl + "?allowMultiQueries=true&autoReconnect=true&maxReconnects=10");
    assertThat(config.tables()).containsExactlyElementsIn(new String[] {"table1", "table2"});
    assertThat(config.dbAuth().getUserName().get()).isEqualTo(testUser);
    assertThat(config.dbAuth().getPassword().get()).isEqualTo(testPassword);
    assertThat(config.waitOn()).isNotNull();
    assertEquals(null, dbConfigContainer.getShardId());
    // Since schemaMapper is now derived from options, it will have the session file context.
    // The original test expected an empty map, but with a session file, it might not be.
    // Let's verify based on the actual session file if it defines shard IDs for new_cart.
    // The "session-file-with-dropped-column.json" does not define shard IDs.
    assertThat(dbConfigContainer.getSrcTableToShardIdColumnMap(schemaMapper, List.of("new_cart")))
        .isEqualTo(new HashMap<>());
  }

  @Test
  public void shardedDbConfigContainerMySqlTest() {
    final String testDriverClassName = "org.apache.derby.jdbc.EmbeddedDriver";
    final String testUrl = "jdbc:mysql://localhost:3306/testDB";
    final String testUser = "user";
    final String testPassword = "password";
    SourceDbToSpannerOptions sourceDbToSpannerOptions =
        PipelineOptionsFactory.as(SourceDbToSpannerOptions.class);
    sourceDbToSpannerOptions.setSourceDbDialect(SQLDialect.MYSQL.name());
    sourceDbToSpannerOptions.setSourceConfigURL(testUrl);
    sourceDbToSpannerOptions.setJdbcDriverClassName(testDriverClassName);
    sourceDbToSpannerOptions.setMaxConnections(150);
    sourceDbToSpannerOptions.setNumPartitions(4000);
    sourceDbToSpannerOptions.setUsername(testUser);
    sourceDbToSpannerOptions.setPassword(testPassword);
    sourceDbToSpannerOptions.setTables("table1,table2");
    mockedStaticJdbcIoWrapper.when(() -> JdbcIoWrapper.of(any())).thenReturn(mockJdbcIoWrapper);

    Shard shard =
        new Shard("shard1", "localhost", "3306", "user", "password", null, null, null, null);

    ShardedJdbcDbConfigContainer dbConfigContainer =
        new ShardedJdbcDbConfigContainer(
            shard, SQLDialect.MYSQL, null, "shard1", "testDB", sourceDbToSpannerOptions);

    PCollection<Integer> dummyPCollection = pipeline.apply(Create.of(1));
    pipeline.run();

    JdbcIOWrapperConfig config =
        dbConfigContainer.getJDBCIOWrapperConfig(
            List.of("table1", "table2"), Wait.on(dummyPCollection));

    assertThat(config.jdbcDriverClassName()).isEqualTo(testDriverClassName);
    assertThat(config.sourceDbURL())
        .isEqualTo(testUrl + "?allowMultiQueries=true&autoReconnect=true&maxReconnects=10");
    assertThat(config.tables()).containsExactlyElementsIn(new String[] {"table1", "table2"});
    assertThat(config.dbAuth().getUserName().get()).isEqualTo(testUser);
    assertThat(config.dbAuth().getPassword().get()).isEqualTo(testPassword);
    assertThat(config.waitOn()).isNotNull();
    assertEquals("shard1", dbConfigContainer.getShardId());
    assertThat(
            dbConfigContainer.getIOWrapper(List.of("table1", "table2"), Wait.on(dummyPCollection)))
        .isEqualTo(mockJdbcIoWrapper);
  }

  @Test
  public void shardedDbConfigContainerPGTest() {
    final String testDriverClassName = "org.apache.derby.jdbc.EmbeddedDriver";
    final String testUrl = "jdbc:postgresql://localhost:3306/testDB";
    final String testUser = "user";
    final String testPassword = "password";
    SourceDbToSpannerOptions sourceDbToSpannerOptions =
        PipelineOptionsFactory.as(SourceDbToSpannerOptions.class);
    sourceDbToSpannerOptions.setSourceDbDialect(SQLDialect.POSTGRESQL.name());
    sourceDbToSpannerOptions.setSourceConfigURL(testUrl);
    sourceDbToSpannerOptions.setJdbcDriverClassName(testDriverClassName);
    sourceDbToSpannerOptions.setMaxConnections(150);
    sourceDbToSpannerOptions.setNumPartitions(4000);
    sourceDbToSpannerOptions.setUsername(testUser);
    sourceDbToSpannerOptions.setPassword(testPassword);
    sourceDbToSpannerOptions.setTables("table1,table2");

    Shard shard =
        new Shard("shard1", "localhost", "3306", "user", "password", null, null, null, null);

    ShardedJdbcDbConfigContainer dbConfigContainer =
        new ShardedJdbcDbConfigContainer(
            shard,
            SQLDialect.POSTGRESQL,
            "testNameSpace",
            "shard1",
            "testDB",
            sourceDbToSpannerOptions);

    PCollection<Integer> dummyPCollection = pipeline.apply(Create.of(1));
    pipeline.run();

    JdbcIOWrapperConfig config =
        dbConfigContainer.getJDBCIOWrapperConfig(
            List.of("table1", "table2"), Wait.on(dummyPCollection));
    assertThat(config.jdbcDriverClassName()).isEqualTo(testDriverClassName);
    assertThat(config.sourceDbURL()).isEqualTo(testUrl + "?currentSchema=testNameSpace");
    assertThat(config.tables()).containsExactlyElementsIn(new String[] {"table1", "table2"});
    assertThat(config.dbAuth().getUserName().get()).isEqualTo(testUser);
    assertThat(config.dbAuth().getPassword().get()).isEqualTo(testPassword);
    assertThat(config.waitOn()).isNotNull();
    assertEquals("shard1", dbConfigContainer.getShardId());
    assertEquals("testNameSpace", dbConfigContainer.getNamespace());
  }

  @After
  public void cleanup() {
    if (mockedStaticJdbcIoWrapper != null) {
      mockedStaticJdbcIoWrapper.close();
      mockedStaticJdbcIoWrapper = null;
    }
  }
}
