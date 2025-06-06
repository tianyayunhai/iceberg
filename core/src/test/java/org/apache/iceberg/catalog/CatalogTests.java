/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.catalog;

import static org.apache.iceberg.types.Types.NestedField.required;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.setMaxStackTraceElementsDisplayed;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.FilesTable;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.HistoryEntry;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.ReachableFileUtil;
import org.apache.iceberg.ReplaceSortOrder;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.TableUtil;
import org.apache.iceberg.TestHelpers;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdatePartitionSpec;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.metrics.CommitReport;
import org.apache.iceberg.metrics.MetricsReport;
import org.apache.iceberg.metrics.MetricsReporter;
import org.apache.iceberg.metrics.ScanReport;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.relocated.com.google.common.collect.Streams;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.CharSequenceSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public abstract class CatalogTests<C extends Catalog & SupportsNamespaces> {
  private static final String BASE_TABLE_LOCATION = "file:/tmp";
  protected static final Namespace NS = Namespace.of("newdb");
  protected static final TableIdentifier TABLE = TableIdentifier.of(NS, "newtable");
  protected static final TableIdentifier RENAMED_TABLE = TableIdentifier.of(NS, "table_renamed");
  protected static final TableIdentifier TBL = TableIdentifier.of("ns", "tbl");

  // Schema passed to create tables
  protected static final Schema SCHEMA =
      new Schema(
          required(3, "id", Types.IntegerType.get(), "unique ID 🤪"),
          required(4, "data", Types.StringType.get()));

  // This is the actual schema for the table, with column IDs reassigned
  protected static final Schema TABLE_SCHEMA =
      new Schema(
          required(1, "id", Types.IntegerType.get(), "unique ID 🤪"),
          required(2, "data", Types.StringType.get()));

  // This is the actual schema for the table, with column IDs reassigned
  protected static final Schema REPLACE_SCHEMA =
      new Schema(
          required(2, "id", Types.IntegerType.get(), "unique ID 🤪"),
          required(3, "data", Types.StringType.get()));

  // another schema that is not the same
  protected static final Schema OTHER_SCHEMA =
      new Schema(required(1, "some_id", Types.IntegerType.get()));

  // Partition spec used to create tables
  protected static final PartitionSpec SPEC =
      PartitionSpec.builderFor(SCHEMA).bucket("id", 16).build();

  protected static final PartitionSpec TABLE_SPEC =
      PartitionSpec.builderFor(TABLE_SCHEMA).bucket("id", 16).build();

  protected static final PartitionSpec REPLACE_SPEC =
      PartitionSpec.builderFor(REPLACE_SCHEMA).bucket("id", 16).withSpecId(1).build();

  // Partition spec used to create tables
  protected static final SortOrder WRITE_ORDER =
      SortOrder.builderFor(SCHEMA).asc(Expressions.bucket("id", 16)).asc("id").build();

  protected static final SortOrder TABLE_WRITE_ORDER =
      SortOrder.builderFor(TABLE_SCHEMA).asc(Expressions.bucket("id", 16)).asc("id").build();

  protected static final SortOrder REPLACE_WRITE_ORDER =
      SortOrder.builderFor(REPLACE_SCHEMA).asc(Expressions.bucket("id", 16)).asc("id").build();

  protected static final DataFile FILE_A =
      DataFiles.builder(SPEC)
          .withPath("/path/to/data-a.parquet")
          .withFileSizeInBytes(10)
          .withPartitionPath("id_bucket=0") // easy way to set partition data for now
          .withRecordCount(2) // needs at least one record or else metrics will filter it out
          .build();

  protected static final DataFile FILE_B =
      DataFiles.builder(SPEC)
          .withPath("/path/to/data-b.parquet")
          .withFileSizeInBytes(10)
          .withPartitionPath("id_bucket=1") // easy way to set partition data for now
          .withRecordCount(2) // needs at least one record or else metrics will filter it out
          .build();

  protected static final DataFile FILE_C =
      DataFiles.builder(SPEC)
          .withPath("/path/to/data-c.parquet")
          .withFileSizeInBytes(10)
          .withPartitionPath("id_bucket=2") // easy way to set partition data for now
          .withRecordCount(2) // needs at least one record or else metrics will filter it out
          .build();

  protected abstract C catalog();

  protected abstract C initCatalog(String catalogName, Map<String, String> additionalProperties);

  protected boolean supportsNamespaceProperties() {
    return true;
  }

  protected boolean supportsNestedNamespaces() {
    return false;
  }

  protected boolean requiresNamespaceCreate() {
    return false;
  }

  protected boolean supportsServerSideRetry() {
    return false;
  }

  protected boolean overridesRequestedLocation() {
    return false;
  }

  protected boolean supportsNamesWithSlashes() {
    return true;
  }

  protected boolean supportsNamesWithDot() {
    return true;
  }

  protected boolean supportsEmptyNamespace() {
    return false;
  }

  protected String baseTableLocation(TableIdentifier identifier) {
    return BASE_TABLE_LOCATION + "/" + identifier.namespace() + "/" + identifier.name();
  }

  @Test
  public void testCreateNamespace() {
    C catalog = catalog();

    assertThat(catalog.namespaceExists(NS)).as("Namespace should not exist").isFalse();

    catalog.createNamespace(NS);
    assertThat(catalog.listNamespaces())
        .as("Catalog should have the created namespace")
        .contains(NS);
    assertThat(catalog.namespaceExists(NS)).as("Namespace should exist").isTrue();
  }

  @Test
  public void testCreateExistingNamespace() {
    C catalog = catalog();

    assertThat(catalog.namespaceExists(NS)).as("Namespace should not exist").isFalse();

    catalog.createNamespace(NS);
    assertThat(catalog.namespaceExists(NS)).as("Namespace should exist").isTrue();

    assertThatThrownBy(() -> catalog.createNamespace(NS))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageContaining("Namespace already exists");

    assertThat(catalog.namespaceExists(NS)).as("Namespace should still exist").isTrue();
  }

  @Test
  public void testCreateNamespaceWithProperties() {
    assumeThat(supportsNamespaceProperties()).isTrue();

    C catalog = catalog();

    assertThat(catalog.namespaceExists(NS)).as("Namespace should not exist").isFalse();

    Map<String, String> createProps = ImmutableMap.of("prop", "val");
    catalog.createNamespace(NS, createProps);
    assertThat(catalog.namespaceExists(NS)).as("Namespace should exist").isTrue();

    Map<String, String> props = catalog.loadNamespaceMetadata(NS);

    assertThat(Sets.intersection(createProps.entrySet(), props.entrySet()))
        .as("Create properties should be a subset of returned properties")
        .containsExactlyInAnyOrderElementsOf(createProps.entrySet());
  }

  @Test
  public void testLoadNamespaceMetadata() {
    C catalog = catalog();

    assertThat(catalog.namespaceExists(NS)).as("Namespace should not exist").isFalse();

    assertThatThrownBy(() -> catalog.loadNamespaceMetadata(NS))
        .isInstanceOf(NoSuchNamespaceException.class)
        .hasMessageStartingWith("Namespace does not exist: %s", NS);

    catalog.createNamespace(NS);
    assertThat(catalog.namespaceExists(NS)).as("Namespace should exist").isTrue();
    Map<String, String> props = catalog.loadNamespaceMetadata(NS);
    assertThat(props).as("Should return non-null property map").isNotNull();
    // note that there are no requirements for the properties returned by the catalog
  }

  @Test
  public void testSetNamespaceProperties() {
    assumeThat(supportsNamespaceProperties()).isTrue();

    C catalog = catalog();

    Map<String, String> properties = ImmutableMap.of("owner", "user", "created-at", "sometime");

    catalog.createNamespace(NS);
    catalog.setProperties(NS, properties);

    Map<String, String> actualProperties = catalog.loadNamespaceMetadata(NS);
    assertThat(actualProperties.entrySet())
        .as("Set properties should be a subset of returned properties")
        .containsAll(properties.entrySet());
  }

  @Test
  public void testUpdateNamespaceProperties() {
    assumeThat(supportsNamespaceProperties()).isTrue();

    C catalog = catalog();

    Map<String, String> initialProperties = ImmutableMap.of("owner", "user");

    catalog.createNamespace(NS);
    catalog.setProperties(NS, initialProperties);

    Map<String, String> actualProperties = catalog.loadNamespaceMetadata(NS);
    assertThat(actualProperties.entrySet())
        .as("Set properties should be a subset of returned properties")
        .containsAll(initialProperties.entrySet());

    Map<String, String> updatedProperties = ImmutableMap.of("owner", "newuser");

    catalog.setProperties(NS, updatedProperties);

    Map<String, String> finalProperties = catalog.loadNamespaceMetadata(NS);
    assertThat(finalProperties.entrySet())
        .as("Updated properties should be a subset of returned properties")
        .containsAll(updatedProperties.entrySet());
  }

  @Test
  public void testUpdateAndSetNamespaceProperties() {
    assumeThat(supportsNamespaceProperties()).isTrue();

    C catalog = catalog();

    Map<String, String> initialProperties = ImmutableMap.of("owner", "user");

    catalog.createNamespace(NS);
    catalog.setProperties(NS, initialProperties);

    Map<String, String> actualProperties = catalog.loadNamespaceMetadata(NS);
    assertThat(actualProperties.entrySet())
        .as("Set properties should be a subset of returned properties")
        .containsAll(initialProperties.entrySet());

    Map<String, String> updatedProperties =
        ImmutableMap.of("owner", "newuser", "last-modified-at", "now");

    catalog.setProperties(NS, updatedProperties);

    Map<String, String> finalProperties = catalog.loadNamespaceMetadata(NS);
    assertThat(finalProperties.entrySet())
        .as("Updated properties should be a subset of returned properties")
        .containsAll(updatedProperties.entrySet());
  }

  @Test
  public void testSetNamespacePropertiesNamespaceDoesNotExist() {
    assumeThat(supportsNamespaceProperties()).isTrue();

    C catalog = catalog();

    assertThatThrownBy(() -> catalog.setProperties(NS, ImmutableMap.of("test", "value")))
        .isInstanceOf(NoSuchNamespaceException.class)
        .hasMessageStartingWith("Namespace does not exist: %s", NS);
  }

  @Test
  public void testRemoveNamespaceProperties() {
    assumeThat(supportsNamespaceProperties()).isTrue();

    C catalog = catalog();

    Map<String, String> properties = ImmutableMap.of("owner", "user", "created-at", "sometime");

    catalog.createNamespace(NS);
    catalog.setProperties(NS, properties);
    catalog.removeProperties(NS, ImmutableSet.of("created-at"));

    Map<String, String> actualProperties = catalog.loadNamespaceMetadata(NS);
    assertThat(actualProperties.containsKey("created-at"))
        .as("Should not contain deleted property key")
        .isFalse();
    assertThat(Sets.intersection(properties.entrySet(), actualProperties.entrySet()))
        .as("Expected properties should be a subset of returned properties")
        .containsExactlyInAnyOrderElementsOf(ImmutableMap.of("owner", "user").entrySet());
  }

  @Test
  public void testRemoveNamespacePropertiesNamespaceDoesNotExist() {
    assumeThat(supportsNamespaceProperties()).isTrue();

    C catalog = catalog();

    assertThatThrownBy(() -> catalog.removeProperties(NS, ImmutableSet.of("a", "b")))
        .isInstanceOf(NoSuchNamespaceException.class)
        .hasMessageStartingWith("Namespace does not exist: %s", NS);
  }

  @Test
  public void testDropNamespace() {
    C catalog = catalog();

    assertThat(catalog.namespaceExists(NS)).as("Namespace should not exist").isFalse();

    catalog.createNamespace(NS);
    assertThat(catalog.namespaceExists(NS)).as("Namespace should exist").isTrue();
    assertThat(catalog.dropNamespace(NS))
        .as("Dropping an existing namespace should return true")
        .isTrue();
    assertThat(catalog.namespaceExists(NS)).as("Namespace should not exist").isFalse();
  }

  @Test
  public void testDropNonexistentNamespace() {
    C catalog = catalog();

    assertThat(catalog.dropNamespace(NS))
        .as("Dropping a nonexistent namespace should return false")
        .isFalse();
  }

  @Test
  public void testDropNonEmptyNamespace() {
    C catalog = catalog();

    assertThat(catalog.namespaceExists(NS)).as("Namespace should not exist").isFalse();

    catalog.createNamespace(NS);
    assertThat(catalog.namespaceExists(NS)).as("Namespace should exist").isTrue();
    catalog.buildTable(TABLE, SCHEMA).create();
    assertThat(catalog.tableExists(TABLE)).as("Table should exist").isTrue();

    assertThatThrownBy(() -> catalog.dropNamespace(NS))
        .isInstanceOf(NamespaceNotEmptyException.class)
        .hasMessageContaining("is not empty");

    catalog.dropTable(TABLE);
    assertThat(catalog.tableExists(TABLE)).as("Table should not exist").isFalse();

    assertThat(catalog.dropNamespace(NS))
        .as("Dropping an existing namespace should return true")
        .isTrue();
    assertThat(catalog.namespaceExists(NS)).as("Namespace should not exist").isFalse();
  }

  @Test
  public void testListNamespaces() {
    C catalog = catalog();
    // the catalog may automatically create a default namespace
    List<Namespace> starting = catalog.listNamespaces();

    Namespace ns1 = Namespace.of("newdb_1");
    Namespace ns2 = Namespace.of("newdb_2");

    catalog.createNamespace(ns1);
    assertThat(catalog.listNamespaces())
        .as("Should include newdb_1")
        .hasSameElementsAs(concat(starting, ns1));

    catalog.createNamespace(ns2);
    assertThat(catalog.listNamespaces())
        .as("Should include newdb_1 and newdb_2")
        .hasSameElementsAs(concat(starting, ns1, ns2));

    catalog.dropNamespace(ns1);
    assertThat(catalog.listNamespaces())
        .as("Should include newdb_2, not newdb_1")
        .hasSameElementsAs(concat(starting, ns2));

    catalog.dropNamespace(ns2);
    assertThat(catalog.listNamespaces().containsAll(starting))
        .as("Should include only starting namespaces")
        .isTrue();
  }

  @Test
  public void testListNestedNamespaces() {
    assumeThat(supportsNestedNamespaces())
        .as("Only valid when the catalog supports nested namespaces")
        .isTrue();

    C catalog = catalog();

    // the catalog may automatically create a default namespace
    List<Namespace> starting = catalog.listNamespaces();

    Namespace parent = Namespace.of("parent");
    Namespace child1 = Namespace.of("parent", "child1");
    Namespace child2 = Namespace.of("parent", "child2");

    catalog.createNamespace(parent);
    assertThat(catalog.listNamespaces())
        .as("Should include parent")
        .hasSameElementsAs(concat(starting, parent));

    assertThat(catalog.listNamespaces(parent))
        .as("Should have no children in newly created parent namespace")
        .isEmpty();

    catalog.createNamespace(child1);
    assertThat(catalog.listNamespaces(parent))
        .as("Should include child1")
        .hasSameElementsAs(ImmutableList.of(child1));

    catalog.createNamespace(child2);
    assertThat(catalog.listNamespaces(parent))
        .as("Should include child1 and child2")
        .hasSameElementsAs(ImmutableList.of(child1, child2));

    assertThat(catalog.listNamespaces())
        .as("Should not change listing the root")
        .hasSameElementsAs(concat(starting, parent));

    catalog.dropNamespace(child1);
    assertThat(catalog.listNamespaces(parent))
        .as("Should include only child2")
        .hasSameElementsAs(ImmutableList.of(child2));

    catalog.dropNamespace(child2);
    assertThat(catalog.listNamespaces(parent)).as("Should be empty").isEmpty();
  }

  @Test
  public void testNamespaceWithSlash() {
    assumeThat(supportsNamesWithSlashes()).isTrue();

    C catalog = catalog();

    Namespace withSlash = Namespace.of("new/db");

    assertThat(catalog.namespaceExists(withSlash)).as("Namespace should not exist").isFalse();

    catalog.createNamespace(withSlash);
    assertThat(catalog.namespaceExists(withSlash)).as("Namespace should exist").isTrue();

    Map<String, String> properties = catalog.loadNamespaceMetadata(withSlash);
    assertThat(properties).as("Properties should be accessible").isNotNull();
    assertThat(catalog.dropNamespace(withSlash))
        .as("Dropping the namespace should succeed")
        .isTrue();
    assertThat(catalog.namespaceExists(withSlash)).as("Namespace should not exist").isFalse();
  }

  @Test
  public void testNamespaceWithDot() {
    assumeThat(supportsNamesWithDot()).isTrue();

    C catalog = catalog();

    Namespace withDot = Namespace.of("new.db");

    assertThat(catalog.namespaceExists(withDot)).as("Namespace should not exist").isFalse();

    catalog.createNamespace(withDot);
    assertThat(catalog.namespaceExists(withDot)).as("Namespace should exist").isTrue();

    assertThat(catalog.listNamespaces()).contains(withDot);

    Map<String, String> properties = catalog.loadNamespaceMetadata(withDot);
    assertThat(properties).as("Properties should be accessible").isNotNull();
    assertThat(catalog.dropNamespace(withDot)).as("Dropping the namespace should succeed").isTrue();
    assertThat(catalog.namespaceExists(withDot)).as("Namespace should not exist").isFalse();
  }

  @Test
  public void testBasicCreateTable() {
    C catalog = catalog();

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    Table table = catalog.buildTable(TBL, SCHEMA).create();
    assertThat(catalog.tableExists(TBL)).as("Table should exist").isTrue();

    // validate table settings
    assertThat(table.name())
        .as("Table name should report its full name")
        .isEqualTo(catalog.name() + "." + TBL);
    assertThat(table.schema().asStruct())
        .as("Schema should match expected ID assignment")
        .isEqualTo(TABLE_SCHEMA.asStruct());
    assertThat(table.location()).as("Should have a location").isNotNull();
    assertThat(table.spec().isUnpartitioned()).as("Should be unpartitioned").isTrue();
    assertThat(table.sortOrder().isUnsorted()).as("Should be unsorted").isTrue();
    assertThat(table.properties()).as("Should have table properties").isNotNull();
  }

  @Test
  public void testTableNameWithSlash() {
    assumeThat(supportsNamesWithSlashes()).isTrue();

    C catalog = catalog();

    TableIdentifier ident = TableIdentifier.of("ns", "tab/le");
    if (requiresNamespaceCreate()) {
      catalog.createNamespace(Namespace.of("ns"));
    }

    assertThat(catalog.tableExists(ident)).as("Table should not exist").isFalse();

    catalog.buildTable(ident, SCHEMA).create();
    assertThat(catalog.tableExists(ident)).as("Table should exist").isTrue();

    Table loaded = catalog.loadTable(ident);
    assertThat(loaded.schema().asStruct())
        .as("Schema should match expected ID assignment")
        .isEqualTo(TABLE_SCHEMA.asStruct());

    catalog.dropTable(ident);

    assertThat(catalog.tableExists(ident)).as("Table should not exist").isFalse();
  }

  @Test
  public void testTableNameWithDot() {
    assumeThat(supportsNamesWithDot()).isTrue();

    C catalog = catalog();

    Namespace namespace = Namespace.of("ns");
    TableIdentifier ident = TableIdentifier.of(namespace, "ta.ble");
    if (requiresNamespaceCreate()) {
      catalog.createNamespace(namespace);
    }

    assertThat(catalog.tableExists(ident)).as("Table should not exist").isFalse();

    catalog.buildTable(ident, SCHEMA).create();
    assertThat(catalog.tableExists(ident)).as("Table should exist").isTrue();
    assertThat(catalog.listTables(namespace)).contains(ident);

    Table loaded = catalog.loadTable(ident);
    assertThat(loaded.schema().asStruct())
        .as("Schema should match expected ID assignment")
        .isEqualTo(TABLE_SCHEMA.asStruct());

    catalog.dropTable(ident);

    assertThat(catalog.tableExists(ident)).as("Table should not exist").isFalse();
  }

  @Test
  public void testBasicCreateTableThatAlreadyExists() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    catalog.buildTable(TBL, SCHEMA).create();
    assertThat(catalog.tableExists(TBL)).as("Table should exist").isTrue();

    assertThatThrownBy(() -> catalog.buildTable(TBL, OTHER_SCHEMA).create())
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageStartingWith("Table already exists: ns.tbl");

    Table table = catalog.loadTable(TBL);
    assertThat(table.schema().asStruct())
        .as("Schema should match original table schema")
        .isEqualTo(TABLE_SCHEMA.asStruct());
  }

  @Test
  public void testCompleteCreateTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    Map<String, String> properties =
        ImmutableMap.of("user", "someone", "created-at", "2022-02-25T00:38:19");
    Table table =
        catalog
            .buildTable(TBL, SCHEMA)
            .withLocation(baseTableLocation(TBL))
            .withPartitionSpec(SPEC)
            .withSortOrder(WRITE_ORDER)
            .withProperties(properties)
            .create();

    // validate table settings
    assertThat(table.name())
        .as("Table name should report its full name")
        .isEqualTo(catalog.name() + "." + TBL);
    assertThat(catalog.tableExists(TBL)).as("Table should exist").isTrue();
    assertThat(table.schema().asStruct())
        .as("Schema should match expected ID assignment")
        .isEqualTo(TABLE_SCHEMA.asStruct());
    assertThat(table.location()).as("Should have a location").isNotNull();
    assertThat(table.spec()).as("Should use requested partition spec").isEqualTo(TABLE_SPEC);
    assertThat(table.sortOrder())
        .as("Should use requested write order")
        .isEqualTo(TABLE_WRITE_ORDER);
    assertThat(table.properties().entrySet())
        .as("Table properties should be a superset of the requested properties")
        .containsAll(properties.entrySet());
    assertThat(table.uuid())
        .isEqualTo(UUID.fromString(((BaseTable) table).operations().current().uuid()));
  }

  @Test
  public void testDefaultTableProperties() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    Table table =
        catalog()
            .buildTable(TBL, SCHEMA)
            .withProperty("default-key2", "catalog-overridden-key2")
            .withProperty("prop1", "val1")
            .create();
    assertThat(table.properties())
        .containsEntry("default-key1", "catalog-default-key1")
        .containsEntry("default-key2", "catalog-overridden-key2")
        .containsEntry("prop1", "val1");

    assertThat(catalog.dropTable(TBL)).as("Should successfully drop table").isTrue();
  }

  @Test
  public void testDefaultTablePropertiesCreateTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    catalog()
        .buildTable(TBL, SCHEMA)
        .withProperty("default-key2", "catalog-overridden-key2")
        .withProperty("prop1", "val1")
        .createTransaction()
        .commitTransaction();

    Table table = catalog.loadTable(TBL);

    assertThat(table.properties())
        .containsEntry("default-key1", "catalog-default-key1")
        .containsEntry("default-key2", "catalog-overridden-key2")
        .containsEntry("prop1", "val1");

    assertThat(catalog.dropTable(TBL)).as("Should successfully drop table").isTrue();
  }

  @Test
  public void testDefaultTablePropertiesReplaceTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    catalog.createTable(TBL, SCHEMA);
    assertThat(catalog.tableExists(TBL)).as("Table should exist").isTrue();

    catalog()
        .buildTable(TBL, OTHER_SCHEMA)
        .withProperty("default-key2", "catalog-overridden-key2")
        .withProperty("prop1", "val1")
        .replaceTransaction()
        .commitTransaction();

    Table table = catalog.loadTable(TBL);

    assertThat(table.properties())
        .containsEntry("default-key1", "catalog-default-key1")
        .containsEntry("default-key2", "catalog-overridden-key2")
        .containsEntry("prop1", "val1");

    assertThat(catalog.dropTable(TBL)).as("Should successfully drop table").isTrue();
  }

  @Test
  public void testOverrideTableProperties() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    Table table =
        catalog()
            .buildTable(TBL, SCHEMA)
            .withProperty("override-key4", "catalog-overridden-key4")
            .withProperty("prop1", "val1")
            .create();
    assertThat(table.properties())
        .containsEntry("default-key1", "catalog-default-key1")
        .containsEntry("default-key2", "catalog-default-key2")
        .containsEntry("override-key3", "catalog-override-key3")
        .containsEntry("override-key4", "catalog-override-key4")
        .containsEntry("prop1", "val1");

    assertThat(catalog.dropTable(TBL)).as("Should successfully drop table").isTrue();
  }

  @Test
  public void testOverrideTablePropertiesCreateTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    catalog()
        .buildTable(TBL, SCHEMA)
        .withProperty("override-key4", "catalog-overridden-key4")
        .withProperty("prop1", "val1")
        .createTransaction()
        .commitTransaction();

    Table table = catalog.loadTable(TBL);

    assertThat(table.properties())
        .containsEntry("default-key1", "catalog-default-key1")
        .containsEntry("default-key2", "catalog-default-key2")
        .containsEntry("override-key3", "catalog-override-key3")
        .containsEntry("override-key4", "catalog-override-key4")
        .containsEntry("prop1", "val1");

    assertThat(catalog.dropTable(TBL)).as("Should successfully drop table").isTrue();
  }

  @Test
  public void testOverrideTablePropertiesReplaceTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    catalog.createTable(TBL, SCHEMA);
    assertThat(catalog.tableExists(TBL)).as("Table should exist").isTrue();

    catalog()
        .buildTable(TBL, OTHER_SCHEMA)
        .withProperty("override-key4", "catalog-overridden-key4")
        .withProperty("prop1", "val1")
        .replaceTransaction()
        .commitTransaction();

    Table table = catalog.loadTable(TBL);

    assertThat(table.properties())
        .containsEntry("default-key1", "catalog-default-key1")
        .containsEntry("default-key2", "catalog-default-key2")
        .containsEntry("override-key3", "catalog-override-key3")
        .containsEntry("override-key4", "catalog-override-key4")
        .containsEntry("prop1", "val1");

    assertThat(catalog.dropTable(TBL)).as("Should successfully drop table").isTrue();
  }

  @Test
  public void testCreateTableWithDefaultColumnValue() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    Schema schemaWithDefault =
        new Schema(
            List.of(
                required("colWithDefault")
                    .withId(1)
                    .ofType(Types.IntegerType.get())
                    .withWriteDefault(Literal.of(10))
                    .withInitialDefault(Literal.of(12))
                    .build()));

    catalog
        .buildTable(TBL, schemaWithDefault)
        .withLocation(baseTableLocation(TBL))
        .withProperty(TableProperties.FORMAT_VERSION, "3")
        .create();
    assertThat(catalog.tableExists(TBL)).as("Table should exist").isTrue();
    assertThat(schemaWithDefault.asStruct()).isEqualTo(catalog.loadTable(TBL).schema().asStruct());
  }

  @Test
  public void testLoadTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TBL.namespace());
    }

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();

    Map<String, String> properties =
        ImmutableMap.of("user", "someone", "created-at", "2022-02-25T00:38:19");
    catalog
        .buildTable(TBL, SCHEMA)
        .withLocation(baseTableLocation(TBL))
        .withPartitionSpec(SPEC)
        .withSortOrder(WRITE_ORDER)
        .withProperties(properties)
        .create();
    assertThat(catalog.tableExists(TBL)).as("Table should exist").isTrue();

    Table table = catalog.loadTable(TBL);
    // validate table settings
    assertThat(table.name())
        .as("Table name should report its full name")
        .isEqualTo(catalog.name() + "." + TBL);
    assertThat(catalog.tableExists(TBL)).as("Table should exist").isTrue();
    assertThat(table.schema().asStruct())
        .as("Schema should match expected ID assignment")
        .isEqualTo(TABLE_SCHEMA.asStruct());
    assertThat(table.location()).as("Should have a location").isNotNull();
    assertThat(table.spec()).as("Should use requested partition spec").isEqualTo(TABLE_SPEC);
    assertThat(table.sortOrder())
        .as("Should use requested write order")
        .isEqualTo(TABLE_WRITE_ORDER);
    assertThat(table.properties().entrySet())
        .as("Table properties should be a superset of the requested properties")
        .containsAll(properties.entrySet());
  }

  @Test
  public void testLoadTableWithNonExistingNamespace() {
    TableIdentifier ident = TableIdentifier.of("non-existing", "tbl");
    assertThat(catalog().tableExists(ident)).as("Table should not exist").isFalse();
    assertThatThrownBy(() -> catalog().loadTable(ident))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessageStartingWith("Table does not exist: %s", ident);
  }

  @Test
  public void testLoadMetadataTable() {
    C catalog = catalog();

    TableIdentifier tableIdent = TableIdentifier.of("ns", "tbl");
    TableIdentifier metaIdent = TableIdentifier.of("ns", "tbl", "files");

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(tableIdent.namespace());
    }

    catalog.buildTable(tableIdent, SCHEMA).create();

    Table table = catalog.loadTable(metaIdent);
    assertThat(table).isNotNull();
    assertThat(table).isInstanceOf(FilesTable.class);

    // check that the table metadata can be refreshed
    table.refresh();

    assertThat(table.name()).isEqualTo(catalog.name() + "." + metaIdent);
  }

  @Test
  public void testLoadMissingTable() {
    C catalog = catalog();

    assertThat(catalog.tableExists(TBL)).as("Table should not exist").isFalse();
    assertThatThrownBy(() -> catalog.loadTable(TBL))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessageStartingWith("Table does not exist: ns.tbl");
  }

  @Test
  public void testRenameTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    assertThat(catalog.tableExists(TABLE))
        .as("Source table should not exist before create")
        .isFalse();

    catalog.buildTable(TABLE, SCHEMA).create();
    assertThat(catalog.tableExists(TABLE)).as("Table should exist after create").isTrue();

    assertThat(catalog.tableExists(RENAMED_TABLE))
        .as("Destination table should not exist before rename")
        .isFalse();

    catalog.renameTable(TABLE, RENAMED_TABLE);
    assertThat(catalog.tableExists(RENAMED_TABLE)).as("Table should exist with new name").isTrue();
    assertThat(catalog.tableExists(TABLE)).as("Original table should no longer exist").isFalse();

    catalog.dropTable(RENAMED_TABLE);
    assertEmpty("Should not contain table after drop", catalog, NS);
  }

  @Test
  public void testRenameTableMissingSourceTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    assertThat(catalog.tableExists(TABLE))
        .as("Source table should not exist before rename")
        .isFalse();
    assertThat(catalog.tableExists(RENAMED_TABLE))
        .as("Destination table should not exist before rename")
        .isFalse();

    assertThatThrownBy(() -> catalog.renameTable(TABLE, RENAMED_TABLE))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessageContaining("Table does not exist");

    assertThat(catalog.tableExists(RENAMED_TABLE))
        .as("Destination table should not exist after failed rename")
        .isFalse();
  }

  @Test
  public void renameTableNamespaceMissing() {
    TableIdentifier from = TableIdentifier.of("ns", "tbl");
    TableIdentifier to = TableIdentifier.of("non_existing", "renamedTable");

    if (requiresNamespaceCreate()) {
      catalog().createNamespace(from.namespace());
    }

    assertThat(catalog().tableExists(from)).as("Table should not exist").isFalse();

    catalog().buildTable(from, SCHEMA).create();

    assertThat(catalog().tableExists(from)).as("Table should exist").isTrue();

    assertThatThrownBy(() -> catalog().renameTable(from, to))
        .isInstanceOf(NoSuchNamespaceException.class)
        .hasMessageContaining("Namespace does not exist: non_existing");
  }

  @Test
  public void testRenameTableDestinationTableAlreadyExists() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    assertThat(catalog.tableExists(TABLE))
        .as("Source table should not exist before create")
        .isFalse();

    catalog.buildTable(TABLE, SCHEMA).create();
    assertThat(catalog.tableExists(TABLE)).as("Source table should exist after create").isTrue();

    assertThat(catalog.tableExists(RENAMED_TABLE))
        .as("Destination table should not exist before create")
        .isFalse();

    catalog.buildTable(RENAMED_TABLE, SCHEMA).create();
    assertThat(catalog.tableExists(RENAMED_TABLE))
        .as("Destination table should exist after create")
        .isTrue();
    assertThatThrownBy(() -> catalog.renameTable(TABLE, RENAMED_TABLE))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageContaining("Table already exists");
    assertThat(catalog.tableExists(TABLE))
        .as("Source table should still exist after failed rename")
        .isTrue();
    assertThat(catalog.tableExists(RENAMED_TABLE))
        .as("Destination table should still exist after failed rename")
        .isTrue();

    String sourceTableUUID =
        ((HasTableOperations) catalog.loadTable(TABLE)).operations().current().uuid();
    String destinationTableUUID =
        ((HasTableOperations) catalog.loadTable(RENAMED_TABLE)).operations().current().uuid();
    assertThat(sourceTableUUID)
        .as("Source and destination table should remain distinct after failed rename")
        .isNotEqualTo(destinationTableUUID);
  }

  @Test
  public void testDropTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    assertThat(catalog.tableExists(TABLE)).as("Table should not exist before create").isFalse();

    catalog.buildTable(TABLE, SCHEMA).create();
    assertThat(catalog.tableExists(TABLE)).as("Table should exist after create").isTrue();

    boolean dropped = catalog.dropTable(TABLE);
    assertThat(dropped).as("Should drop a table that does exist").isTrue();
    assertThat(catalog.tableExists(TABLE)).as("Table should not exist after drop").isFalse();
  }

  @Test
  public void testDropTableWithPurge() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    assertThat(catalog.tableExists(TABLE)).as("Table should not exist before create").isFalse();

    catalog.buildTable(TABLE, SCHEMA).create();
    assertThat(catalog.tableExists(TABLE)).as("Table should exist after create").isTrue();

    boolean dropped = catalog.dropTable(TABLE, true);
    assertThat(dropped).as("Should drop a table that does exist").isTrue();
    assertThat(catalog.tableExists(TABLE)).as("Table should not exist after drop").isFalse();
  }

  @Test
  public void testDropTableWithoutPurge() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    assertThat(catalog.tableExists(TABLE)).as("Table should not exist before create").isFalse();

    Table table = catalog.buildTable(TABLE, SCHEMA).create();
    assertThat(catalog.tableExists(TABLE)).as("Table should exist after create").isTrue();
    Set<String> actualMetadataFileLocations = ReachableFileUtil.metadataFileLocations(table, false);

    boolean dropped = catalog.dropTable(TABLE, false);
    assertThat(dropped).as("Should drop a table that does exist").isTrue();
    assertThat(catalog.tableExists(TABLE)).as("Table should not exist after drop").isFalse();
    Set<String> expectedMetadataFileLocations =
        ReachableFileUtil.metadataFileLocations(table, false);
    assertThat(actualMetadataFileLocations)
        .hasSameElementsAs(expectedMetadataFileLocations)
        .hasSize(1)
        .as("Should have one metadata file");
  }

  @Test
  public void testDropMissingTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    TableIdentifier noSuchTableIdent = TableIdentifier.of(NS, "notable");
    assertThat(catalog.tableExists(noSuchTableIdent)).as("Table should not exist").isFalse();
    assertThat(catalog.dropTable(noSuchTableIdent))
        .as("Should not drop a table that does not exist")
        .isFalse();
  }

  @Test
  public void testListTables() {
    C catalog = catalog();

    Namespace ns1 = Namespace.of("ns_1");
    Namespace ns2 = Namespace.of("ns_2");

    TableIdentifier ns1Table1 = TableIdentifier.of(ns1, "table_1");
    TableIdentifier ns1Table2 = TableIdentifier.of(ns1, "table_2");
    TableIdentifier ns2Table1 = TableIdentifier.of(ns2, "table_1");

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(ns1);
      catalog.createNamespace(ns2);
    }

    assertEmpty("Should not have tables in a new namespace, ns_1", catalog, ns1);
    assertEmpty("Should not have tables in a new namespace, ns_2", catalog, ns2);

    catalog.buildTable(ns1Table1, SCHEMA).create();

    assertThat(catalog.listTables(ns1))
        .as("Should contain ns_1.table_1 after create")
        .containsExactlyInAnyOrder(ns1Table1);

    catalog.buildTable(ns2Table1, SCHEMA).create();

    assertThat(catalog.listTables(ns2))
        .as("Should contain ns_2.table_1 after create")
        .containsExactlyInAnyOrder(ns2Table1);
    assertThat(catalog.listTables(ns1))
        .as("Should not show changes to ns_2 in ns_1")
        .containsExactlyInAnyOrder(ns1Table1);

    catalog.buildTable(ns1Table2, SCHEMA).create();

    assertThat(catalog.listTables(ns2))
        .as("Should not show changes to ns_1 in ns_2")
        .containsExactlyInAnyOrder(ns2Table1);
    assertThat(catalog.listTables(ns1))
        .as("Should contain ns_1.table_2 after create")
        .containsExactlyInAnyOrder(ns1Table1, ns1Table2);

    catalog.dropTable(ns1Table1);

    assertThat(catalog.listTables(ns2))
        .as("Should not show changes to ns_1 in ns_2")
        .containsExactlyInAnyOrder(ns2Table1);
    assertThat(catalog.listTables(ns1))
        .as("Should not contain ns_1.table_1 after drop")
        .containsExactlyInAnyOrder(ns1Table2);

    catalog.dropTable(ns1Table2);

    assertThat(catalog.listTables(ns2))
        .as("Should not show changes to ns_1 in ns_2")
        .containsExactlyInAnyOrder(ns2Table1);

    assertEmpty("Should not contain ns_1.table_2 after drop", catalog, ns1);

    catalog.dropTable(ns2Table1);
    assertEmpty("Should not contain ns_2.table_1 after drop", catalog, ns2);
  }

  @Test
  public void listNamespacesWithEmptyNamespace() {
    assumeThat(supportsEmptyNamespace())
        .as("Only valid for catalogs that support empty namespaces")
        .isTrue();
    catalog().createNamespace(NS);

    assertThat(catalog().namespaceExists(Namespace.empty())).isFalse();
    assertThat(catalog().listNamespaces()).contains(NS).doesNotContain(Namespace.empty());
    assertThat(catalog().listNamespaces(Namespace.empty()))
        .contains(NS)
        .doesNotContain(Namespace.empty());
  }

  @Test
  public void testListNonExistingNamespace() {
    assertThatThrownBy(() -> catalog().listNamespaces(Namespace.of("non-existing")))
        .isInstanceOf(NoSuchNamespaceException.class)
        .hasMessage("Namespace does not exist: non-existing");
  }

  @Test
  public void createAndDropEmptyNamespace() {
    assumeThat(supportsEmptyNamespace())
        .as("Only valid for catalogs that support creating/dropping empty namespaces")
        .isTrue();

    assertThat(catalog().namespaceExists(Namespace.empty())).isFalse();
    catalog().createNamespace(Namespace.empty());
    assertThat(catalog().namespaceExists(Namespace.empty())).isTrue();

    // TODO: if a catalog supports creating an empty namespace, what should be the expected behavior
    // when listing all namespaces?
    assertThat(catalog().listNamespaces()).isEmpty();
    assertThat(catalog().listNamespaces(Namespace.empty())).isEmpty();

    catalog().dropNamespace(Namespace.empty());
    assertThat(catalog().namespaceExists(Namespace.empty())).isFalse();
  }

  @Test
  public void namespacePropertiesOnEmptyNamespace() {
    assumeThat(supportsEmptyNamespace())
        .as("Only valid for catalogs that support properties on empty namespaces")
        .isTrue();

    catalog().createNamespace(Namespace.empty());

    Map<String, String> properties = ImmutableMap.of("owner", "user", "created-at", "sometime");
    catalog().setProperties(Namespace.empty(), properties);

    assertThat(catalog().loadNamespaceMetadata(Namespace.empty())).containsAllEntriesOf(properties);

    catalog().removeProperties(Namespace.empty(), ImmutableSet.of("owner"));
    assertThat(catalog().loadNamespaceMetadata(Namespace.empty()))
        .containsAllEntriesOf(ImmutableMap.of("created-at", "sometime"));
  }

  @Test
  public void listTablesInEmptyNamespace() {
    assumeThat(supportsEmptyNamespace())
        .as("Only valid for catalogs that support listing tables in empty namespaces")
        .isTrue();

    if (requiresNamespaceCreate()) {
      catalog().createNamespace(Namespace.empty());
      catalog().createNamespace(NS);
    }

    TableIdentifier table1 = TableIdentifier.of(Namespace.empty(), "table_1");
    TableIdentifier table2 = TableIdentifier.of(NS, "table_2");

    catalog().buildTable(table1, SCHEMA).create();
    catalog().buildTable(table2, SCHEMA).create();

    assertThat(catalog().listTables(Namespace.empty())).containsExactly(table1);
  }

  @Test
  public void testUpdateTableSchema() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();
    UpdateSchema update = table.updateSchema().addColumn("new_col", Types.LongType.get());

    Schema expected = update.apply();

    update.commit();

    Table loaded = catalog.loadTable(TABLE);

    assertThat(loaded.schema().asStruct())
        .as("Loaded table should have expected schema")
        .isEqualTo(expected.asStruct());
  }

  @Test
  public void testUUIDValidation() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();
    UpdateSchema update = table.updateSchema().addColumn("new_col", Types.LongType.get());

    assertThat(catalog.dropTable(TABLE)).as("Should successfully drop table").isTrue();
    catalog.buildTable(TABLE, OTHER_SCHEMA).create();

    String expectedMessage =
        supportsServerSideRetry() ? "Requirement failed: UUID does not match" : "Cannot commit";
    assertThatThrownBy(update::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessageContaining(expectedMessage);

    Table loaded = catalog.loadTable(TABLE);
    assertThat(loaded.schema().asStruct())
        .as("Loaded table should have expected schema")
        .isEqualTo(OTHER_SCHEMA.asStruct());
  }

  @Test
  public void testUpdateTableSchemaServerSideRetry() {
    assumeThat(supportsServerSideRetry())
        .as("Schema update recovery is only supported with server-side retry")
        .isTrue();
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();

    UpdateSchema update = table.updateSchema().addColumn("new_col", Types.LongType.get());
    Schema expected = update.apply();

    // update the spec concurrently so that the first update fails, but can succeed on retry
    catalog.loadTable(TABLE).updateSpec().addField("shard", Expressions.bucket("id", 16)).commit();

    // commit the original update
    update.commit();

    Table loaded = catalog.loadTable(TABLE);
    assertThat(loaded.schema().asStruct())
        .as("Loaded table should have expected schema")
        .isEqualTo(expected.asStruct());
  }

  @Test
  public void testUpdateTableSchemaConflict() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();

    UpdateSchema update = table.updateSchema().addColumn("new_col", Types.LongType.get());

    // update the schema concurrently so that the original update fails
    UpdateSchema concurrent = catalog.loadTable(TABLE).updateSchema().deleteColumn("data");
    Schema expected = concurrent.apply();
    concurrent.commit();

    // attempt to commit the original update
    String expectedMessage =
        supportsServerSideRetry() ? "Requirement failed: current schema changed" : "Cannot commit";
    assertThatThrownBy(update::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessageContaining(expectedMessage);

    Table loaded = catalog.loadTable(TABLE);
    assertThat(loaded.schema().asStruct())
        .as("Loaded table should have expected schema")
        .isEqualTo(expected.asStruct());
  }

  @Test
  public void testUpdateTableSchemaAssignmentConflict() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();

    UpdateSchema update = table.updateSchema().addColumn("new_col", Types.LongType.get());

    // update the schema concurrently so that the original update fails
    UpdateSchema concurrent =
        catalog.loadTable(TABLE).updateSchema().addColumn("another_col", Types.StringType.get());
    Schema expected = concurrent.apply();
    concurrent.commit();

    // attempt to commit the original update
    String expectedMessage =
        supportsServerSideRetry()
            ? "Requirement failed: last assigned field id changed"
            : "Cannot commit";
    assertThatThrownBy(update::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessageContaining(expectedMessage);

    Table loaded = catalog.loadTable(TABLE);
    assertThat(loaded.schema().asStruct())
        .as("Loaded table should have expected schema")
        .isEqualTo(expected.asStruct());
  }

  @Test
  public void testUpdateTableSchemaThenRevert() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();

    table
        .updateSchema()
        .addColumn("col1", Types.StringType.get())
        .addColumn("col2", Types.StringType.get())
        .addColumn("col3", Types.StringType.get())
        .commit();

    table.updateSchema().deleteColumn("col1").deleteColumn("col2").deleteColumn("col3").commit();

    assertThat(table.schema().asStruct())
        .as("Loaded table should have expected schema")
        .isEqualTo(TABLE_SCHEMA.asStruct());
  }

  @Test
  public void testUpdateTableSpec() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();
    UpdatePartitionSpec update = table.updateSpec().addField("shard", Expressions.bucket("id", 16));

    PartitionSpec expected = update.apply();

    update.commit();

    Table loaded = catalog.loadTable(TABLE);

    // the spec ID may not match, so check equality of the fields
    assertThat(loaded.spec().fields())
        .as("Loaded table should have expected spec")
        .isEqualTo(expected.fields());
  }

  @Test
  public void testUpdateTableSpecServerSideRetry() {
    assumeThat(supportsServerSideRetry())
        .as("Spec update recovery is only supported with server-side retry")
        .isTrue();
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();

    UpdatePartitionSpec update = table.updateSpec().addField("shard", Expressions.bucket("id", 16));
    PartitionSpec expected = update.apply();

    // update the schema concurrently so that the first update fails, but can succeed on retry
    catalog
        .loadTable(TABLE)
        .updateSchema()
        .addColumn("another_col", Types.StringType.get())
        .commit();

    // commit the original update
    update.commit();

    Table loaded = catalog.loadTable(TABLE);

    // the spec ID may not match, so check equality of the fields
    assertThat(loaded.spec().fields())
        .as("Loaded table should have expected spec")
        .isEqualTo(expected.fields());
  }

  @Test
  public void testUpdateTableSpecConflict() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).withPartitionSpec(SPEC).create();

    UpdatePartitionSpec update =
        table.updateSpec().addField("shard", Expressions.bucket("data", 16));

    // update the spec concurrently so that the original update fails
    UpdatePartitionSpec concurrent =
        catalog.loadTable(TABLE).updateSpec().removeField(Expressions.bucket("id", 16));
    PartitionSpec expected = concurrent.apply();
    concurrent.commit();

    // attempt to commit the original update
    String expectedMessage =
        supportsServerSideRetry()
            ? "Requirement failed: default partition spec changed"
            : "Cannot commit";
    assertThatThrownBy(update::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessageContaining(expectedMessage);

    Table loaded = catalog.loadTable(TABLE);

    // the spec ID may not match, so check equality of the fields
    assertThat(loaded.spec().fields())
        .as("Loaded table should have expected spec")
        .isEqualTo(expected.fields());
  }

  @Test
  public void testUpdateTableAssignmentSpecConflict() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();

    UpdatePartitionSpec update = table.updateSpec().addField("shard", Expressions.bucket("id", 16));

    // update the spec concurrently so that the original update fails
    UpdatePartitionSpec concurrent =
        catalog.loadTable(TABLE).updateSpec().addField("shard", Expressions.truncate("id", 100));
    PartitionSpec expected = concurrent.apply();
    concurrent.commit();

    // attempt to commit the original update
    String expectedMessage =
        supportsServerSideRetry()
            ? "Requirement failed: last assigned partition id changed"
            : "Cannot commit";
    assertThatThrownBy(update::commit)
        .isInstanceOf(CommitFailedException.class)
        .hasMessageContaining(expectedMessage);

    Table loaded = catalog.loadTable(TABLE);

    // the spec ID may not match, so check equality of the fields
    assertThat(loaded.spec().fields())
        .as("Loaded table should have expected spec")
        .isEqualTo(expected.fields());
  }

  @Test
  public void testUpdateTableSpecThenRevert() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    // create a v2 table. otherwise the spec update would produce a different spec with a void
    // partition field
    Table table =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withPartitionSpec(SPEC)
            .withProperty("format-version", "2")
            .create();
    assertThat(TableUtil.formatVersion(table)).as("Should be a v2 table").isEqualTo(2);

    table.updateSpec().addField("id").commit();

    table.updateSpec().removeField("id").commit();

    assertThat(table.spec()).as("Loaded table should have expected spec").isEqualTo(TABLE_SPEC);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testRemoveUnusedSpec(boolean withBranch) {
    String branch = "test";
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withPartitionSpec(SPEC)
            .withProperty(TableProperties.GC_ENABLED, "true")
            .create();
    PartitionSpec spec = table.spec();
    // added a file to trigger snapshot expiration
    table.newFastAppend().appendFile(FILE_A).commit();
    if (withBranch) {
      table.manageSnapshots().createBranch(branch).commit();
    }
    table.updateSpec().addField(Expressions.bucket("data", 16)).commit();
    table.updateSpec().removeField(Expressions.bucket("data", 16)).commit();
    table.updateSpec().addField("data").commit();
    assertThat(table.specs()).as("Should have 3 total specs").hasSize(3);
    PartitionSpec current = table.spec();
    table.expireSnapshots().cleanExpiredMetadata(true).commit();

    Table loaded = catalog.loadTable(TABLE);
    assertThat(loaded.specs().values()).containsExactlyInAnyOrder(spec, current);

    // add a data file with current spec and remove the old data file
    table.newDelete().deleteFile(FILE_A).commit();
    DataFile anotherFile =
        DataFiles.builder(current)
            .withPath("/path/to/data-b.parquet")
            .withFileSizeInBytes(10)
            .withPartitionPath("id_bucket=0/data=123") // easy way to set partition data for now
            .withRecordCount(2) // needs at least one record or else metrics will filter it out
            .build();
    table.newAppend().appendFile(anotherFile).commit();
    table
        .expireSnapshots()
        .cleanExpiredFiles(false)
        .expireOlderThan(table.currentSnapshot().timestampMillis())
        .cleanExpiredMetadata(true)
        .commit();
    loaded = catalog.loadTable(TABLE);
    if (withBranch) {
      assertThat(loaded.specs().values()).containsExactlyInAnyOrder(spec, current);
    } else {
      assertThat(loaded.specs().values()).containsExactlyInAnyOrder(current);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testRemoveUnusedSchemas(boolean withBranch) {
    String branch = "test";
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withPartitionSpec(SPEC)
            .withProperty(TableProperties.GC_ENABLED, "true")
            .create();

    table.newFastAppend().appendFile(FILE_A).commit();
    Snapshot firstSnapshot = table.currentSnapshot();
    if (withBranch) {
      table.manageSnapshots().createBranch(branch).commit();
    }

    table.updateSchema().addColumn("col_to_delete", Types.IntegerType.get()).commit();
    table.updateSchema().deleteColumn("col_to_delete").commit();
    table.updateSchema().addColumn("extra_col", Types.StringType.get()).commit();

    assertThat(table.schemas().values()).as("Should have 3 total schemas").hasSize(3);

    // Keeps the schema used by the single snapshot and the current schema.
    // Doesn't remove snapshots.
    table.expireSnapshots().cleanExpiredMetadata(true).commit();

    Table loaded = catalog.loadTable(TABLE);
    assertThat(loaded.snapshot(firstSnapshot.snapshotId())).isNotNull();
    assertThat(loaded.schemas().keySet())
        .containsExactlyInAnyOrder(firstSnapshot.schemaId(), loaded.schema().schemaId());

    table.updateSchema().addColumn("extra_col2", Types.LongType.get()).commit();
    table.newFastAppend().appendFile(FILE_B).commit();

    table
        .expireSnapshots()
        .expireOlderThan(table.currentSnapshot().timestampMillis())
        .cleanExpiredMetadata(true)
        .commit();

    loaded = catalog.loadTable(TABLE);
    if (withBranch) {
      assertThat(loaded.snapshots())
          .containsExactlyInAnyOrder(firstSnapshot, loaded.currentSnapshot());
      assertThat(loaded.schemas().keySet())
          .containsExactlyInAnyOrder(firstSnapshot.schemaId(), loaded.currentSnapshot().schemaId());
    } else {
      assertThat(loaded.snapshot(firstSnapshot.snapshotId())).isNull();
      assertThat(loaded.schemas().keySet())
          .containsExactlyInAnyOrder(loaded.currentSnapshot().schemaId());
    }
  }

  @Test
  public void testUpdateTableSortOrder() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();
    ReplaceSortOrder update = table.replaceSortOrder().asc(Expressions.bucket("id", 16)).asc("id");

    SortOrder expected = update.apply();

    update.commit();

    Table loaded = catalog.loadTable(TABLE);

    // the sort order ID may not match, so check equality of the fields
    assertThat(loaded.sortOrder().fields())
        .as("Loaded table should have expected order")
        .isEqualTo(expected.fields());
  }

  @Test
  public void testUpdateTableSortOrderServerSideRetry() {
    assumeThat(supportsServerSideRetry())
        .as("Sort order update recovery is only supported with server-side retry")
        .isTrue();
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();

    ReplaceSortOrder update = table.replaceSortOrder().asc(Expressions.bucket("id", 16)).asc("id");
    SortOrder expected = update.apply();

    // update the schema concurrently so that the first update fails, but can succeed on retry
    catalog
        .loadTable(TABLE)
        .updateSchema()
        .addColumn("another_col", Types.StringType.get())
        .commit();

    // commit the original update
    update.commit();

    Table loaded = catalog.loadTable(TABLE);

    // the sort order ID may not match, so check equality of the fields
    assertThat(loaded.sortOrder().fields())
        .as("Loaded table should have expected order")
        .isEqualTo(expected.fields());
  }

  @Test
  public void testUpdateTableOrderThenRevert() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).withSortOrder(WRITE_ORDER).create();

    table.replaceSortOrder().asc("id").commit();

    table.replaceSortOrder().asc(Expressions.bucket("id", 16)).asc("id").commit();

    assertThat(table.sortOrder())
        .as("Loaded table should have expected order")
        .isEqualTo(TABLE_WRITE_ORDER);
  }

  @Test
  public void testAppend() throws IOException {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).withPartitionSpec(SPEC).create();

    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      assertThat(tasks.iterator().hasNext()).as("Should contain no files").isFalse();
    }

    table.newFastAppend().appendFile(FILE_A).commit();

    assertFiles(table, FILE_A);
  }

  @Test
  public void testConcurrentAppendEmptyTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).withPartitionSpec(SPEC).create();

    assertNoFiles(table);

    // create an uncommitted append
    AppendFiles append = table.newFastAppend().appendFile(FILE_A);
    append.apply(); // apply changes to eagerly write metadata

    catalog.loadTable(TABLE).newFastAppend().appendFile(FILE_B).commit();
    assertFiles(catalog.loadTable(TABLE), FILE_B);

    // the uncommitted append should retry and succeed
    append.commit();
    assertFiles(catalog.loadTable(TABLE), FILE_A, FILE_B);
  }

  @Test
  public void testConcurrentAppendNonEmptyTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).withPartitionSpec(SPEC).create();

    assertNoFiles(table);

    // TODO: skip the initial refresh in FastAppend so that commits actually fail

    // create an initial snapshot
    catalog.loadTable(TABLE).newFastAppend().appendFile(FILE_C).commit();

    // create an uncommitted append
    AppendFiles append = table.newFastAppend().appendFile(FILE_A);
    append.apply(); // apply changes to eagerly write metadata

    catalog.loadTable(TABLE).newFastAppend().appendFile(FILE_B).commit();
    assertFiles(catalog.loadTable(TABLE), FILE_B, FILE_C);

    // the uncommitted append should retry and succeed
    append.commit();
    assertFiles(catalog.loadTable(TABLE), FILE_A, FILE_B, FILE_C);
  }

  @Test
  public void testUpdateTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();

    Transaction transaction = table.newTransaction();

    UpdateSchema updateSchema =
        transaction.updateSchema().addColumn("new_col", Types.LongType.get());
    Schema expectedSchema = updateSchema.apply();
    updateSchema.commit();

    UpdatePartitionSpec updateSpec =
        transaction.updateSpec().addField("shard", Expressions.bucket("id", 16));
    PartitionSpec expectedSpec = updateSpec.apply();
    updateSpec.commit();

    transaction.commitTransaction();

    Table loaded = catalog.loadTable(TABLE);

    assertThat(loaded.schema().asStruct())
        .as("Loaded table should have expected schema")
        .isEqualTo(expectedSchema.asStruct());
    assertThat(loaded.spec().fields())
        .as("Loaded table should have expected spec")
        .isEqualTo(expectedSpec.fields());

    assertPreviousMetadataFileCount(loaded, 1);
  }

  @Test
  public void testCreateTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction create = catalog.buildTable(TABLE, SCHEMA).createTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after createTransaction")
        .isFalse();

    create.newFastAppend().appendFile(FILE_A).commit();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after append commit")
        .isFalse();

    create.commitTransaction();

    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();
    Table table = catalog.loadTable(TABLE);
    assertFiles(table, FILE_A);
    assertPreviousMetadataFileCount(table, 0);
  }

  @Test
  public void testCompleteCreateTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Map<String, String> properties =
        ImmutableMap.of("user", "someone", "created-at", "2022-02-25T00:38:19");
    Transaction create =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withLocation("file:/tmp/ns/table")
            .withPartitionSpec(SPEC)
            .withSortOrder(WRITE_ORDER)
            .withProperties(properties)
            .createTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after createTransaction")
        .isFalse();

    create.newFastAppend().appendFile(FILE_A).commit();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after append commit")
        .isFalse();

    create.commitTransaction();

    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();

    Table table = catalog.loadTable(TABLE);
    assertThat(table.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(TABLE_SCHEMA.asStruct());
    assertThat(table.spec().fields())
        .as("Table should have create partition spec")
        .isEqualTo(TABLE_SPEC.fields());
    assertThat(table.sortOrder())
        .as("Table should have create sort order")
        .isEqualTo(TABLE_WRITE_ORDER);
    assertThat(table.properties().entrySet())
        .as("Table properties should be a superset of the requested properties")
        .containsAll(properties.entrySet());
    if (!overridesRequestedLocation()) {
      assertThat(table.location())
          .as("Table location should match requested")
          .isEqualTo("file:/tmp/ns/table");
    }
    assertFiles(table, FILE_A);
    assertFilesPartitionSpec(table);
    assertPreviousMetadataFileCount(table, 0);
  }

  @Test
  public void testCompleteCreateTransactionMultipleSchemas() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Map<String, String> properties =
        ImmutableMap.of("user", "someone", "created-at", "2022-02-25T00:38:19");
    Transaction create =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withLocation(baseTableLocation(TABLE))
            .withPartitionSpec(SPEC)
            .withSortOrder(WRITE_ORDER)
            .withProperties(properties)
            .createTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after createTransaction")
        .isFalse();

    create.newFastAppend().appendFile(FILE_A).commit();

    UpdateSchema updateSchema = create.updateSchema().addColumn("new_col", Types.LongType.get());
    Schema newSchema = updateSchema.apply();
    updateSchema.commit();

    UpdatePartitionSpec updateSpec = create.updateSpec().addField("new_col");
    updateSpec.commit();

    ReplaceSortOrder replaceSortOrder = create.replaceSortOrder().asc("new_col");
    SortOrder newSortOrder = replaceSortOrder.apply();
    replaceSortOrder.commit();

    // Get new spec after commit to write new file with new spec
    PartitionSpec newSpec = create.table().spec();

    DataFile anotherFile =
        DataFiles.builder(newSpec)
            .withPath("/path/to/data-b.parquet")
            .withFileSizeInBytes(10)
            .withPartitionPath("id_bucket=0/new_col=0") // easy way to set partition data for now
            .withRecordCount(2) // needs at least one record or else metrics will filter it out
            .build();

    create.newFastAppend().appendFile(anotherFile).commit();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after append commit")
        .isFalse();

    create.commitTransaction();

    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();

    Table table = catalog.loadTable(TABLE);

    // initial IDs taken from TableMetadata constants
    final int initialSchemaId = 0;
    final int initialSpecId = 0;
    final int initialOrderId = 1;
    final int updateSchemaId = initialSchemaId + 1;
    final int updateSpecId = initialSpecId + 1;
    final int updateOrderId = initialOrderId + 1;

    assertThat(table.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(newSchema.asStruct());
    assertThat(table.schema().schemaId())
        .as("Table schema should match the new schema ID")
        .isEqualTo(updateSchemaId);
    assertThat(table.spec().fields())
        .as("Table should have updated partition spec")
        .isEqualTo(newSpec.fields());
    assertThat(table.spec().specId())
        .as("Table should have updated partition spec ID")
        .isEqualTo(updateSpecId);
    assertThat(table.sortOrder().fields())
        .as("Table should have updated sort order")
        .isEqualTo(newSortOrder.fields());
    assertThat(table.sortOrder().orderId())
        .as("Table should have updated sort order ID")
        .isEqualTo(updateOrderId);
    assertThat(table.properties().entrySet())
        .as("Table properties should be a superset of the requested properties")
        .containsAll(properties.entrySet());
    if (!overridesRequestedLocation()) {
      assertThat(table.location())
          .as("Table location should match requested")
          .isEqualTo(baseTableLocation(TABLE));
    }
    assertFiles(table, FILE_A, anotherFile);
    assertFilePartitionSpec(table, FILE_A, initialSpecId);
    assertFilePartitionSpec(table, anotherFile, updateSpecId);
    assertPreviousMetadataFileCount(table, 0);
  }

  @Test
  public void testCompleteCreateTransactionV2() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Map<String, String> properties =
        ImmutableMap.of(
            "user", "someone", "created-at", "2022-02-25T00:38:19", "format-version", "2");

    Transaction create =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withLocation(baseTableLocation(TABLE))
            .withPartitionSpec(SPEC)
            .withSortOrder(WRITE_ORDER)
            .withProperties(properties)
            .createTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after createTransaction")
        .isFalse();

    create.newFastAppend().appendFile(FILE_A).commit();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after append commit")
        .isFalse();

    create.commitTransaction();

    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();
    Table table = catalog.loadTable(TABLE);

    Map<String, String> expectedProps = Maps.newHashMap(properties);

    expectedProps.remove("format-version");

    assertThat(table.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(TABLE_SCHEMA.asStruct());
    assertThat(table.spec().fields())
        .as("Table should have create partition spec")
        .isEqualTo(TABLE_SPEC.fields());
    assertThat(table.sortOrder())
        .as("Table should have create sort order")
        .isEqualTo(TABLE_WRITE_ORDER);
    assertThat(Sets.intersection(properties.entrySet(), table.properties().entrySet()))
        .as("Table properties should be a superset of the requested properties")
        .containsExactlyInAnyOrderElementsOf(expectedProps.entrySet());
    assertThat(table.currentSnapshot().sequenceNumber())
        .as("Sequence number should start at 1 for v2 format")
        .isEqualTo(1);
    if (!overridesRequestedLocation()) {
      assertThat(table.location())
          .as("Table location should match requested")
          .isEqualTo(baseTableLocation(TABLE));
    }

    assertFiles(table, FILE_A);
    assertFilesPartitionSpec(table);
    assertPreviousMetadataFileCount(table, 0);
  }

  @Test
  public void testConcurrentCreateTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction create = catalog.buildTable(TABLE, SCHEMA).createTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after createTransaction")
        .isFalse();

    create.newFastAppend().appendFile(FILE_A).commit();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after append commit")
        .isFalse();

    catalog.buildTable(TABLE, OTHER_SCHEMA).create();

    setMaxStackTraceElementsDisplayed(Integer.MAX_VALUE);
    String expectedMessage =
        supportsServerSideRetry()
            ? "Requirement failed: table already exists"
            : "Table already exists";
    assertThatThrownBy(create::commitTransaction)
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageStartingWith(expectedMessage);

    // validate the concurrently created table is unmodified
    Table table = catalog.loadTable(TABLE);
    assertThat(table.schema().asStruct())
        .as("Table schema should match concurrent create")
        .isEqualTo(OTHER_SCHEMA.asStruct());
    assertNoFiles(table);
  }

  @Test
  public void testCreateOrReplaceTransactionCreate() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction create = catalog.buildTable(TABLE, SCHEMA).createOrReplaceTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after createTransaction")
        .isFalse();

    create.newFastAppend().appendFile(FILE_A).commit();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after append commit")
        .isFalse();

    create.commitTransaction();

    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();

    Table table = catalog.loadTable(TABLE);
    assertFiles(table, FILE_A);
    assertPreviousMetadataFileCount(table, 0);
  }

  @Test
  public void testCompleteCreateOrReplaceTransactionCreate() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Map<String, String> properties =
        ImmutableMap.of("user", "someone", "created-at", "2022-02-25T00:38:19");
    Transaction createOrReplace =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withLocation(baseTableLocation(TABLE))
            .withPartitionSpec(SPEC)
            .withSortOrder(WRITE_ORDER)
            .withProperties(properties)
            .createOrReplaceTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after createTransaction")
        .isFalse();

    createOrReplace.newFastAppend().appendFile(FILE_A).commit();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after append commit")
        .isFalse();

    createOrReplace.commitTransaction();

    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();

    Table table = catalog.loadTable(TABLE);

    assertThat(table.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(TABLE_SCHEMA.asStruct());
    assertThat(table.spec().fields())
        .as("Table should have create partition spec")
        .isEqualTo(TABLE_SPEC.fields());
    assertThat(table.sortOrder())
        .as("Table should have create sort order")
        .isEqualTo(TABLE_WRITE_ORDER);
    assertThat(table.properties().entrySet())
        .as("Table properties should be a superset of the requested properties")
        .containsAll(properties.entrySet());
    if (!overridesRequestedLocation()) {
      assertThat(table.location())
          .as("Table location should match requested")
          .isEqualTo(baseTableLocation(TABLE));
    }

    assertFiles(table, FILE_A);
    assertFilesPartitionSpec(table);
    assertPreviousMetadataFileCount(table, 0);
  }

  @Test
  public void testCreateOrReplaceReplaceTransactionReplace() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table original = catalog.buildTable(TABLE, OTHER_SCHEMA).create();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should exist before replaceTransaction")
        .isTrue();

    Transaction createOrReplace = catalog.buildTable(TABLE, SCHEMA).createOrReplaceTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should still exist after replaceTransaction")
        .isTrue();

    createOrReplace.newFastAppend().appendFile(FILE_A).commit();

    // validate table has not changed
    Table table = catalog.loadTable(TABLE);

    assertThat(table.schema().asStruct())
        .as("Table schema should match concurrent create")
        .isEqualTo(OTHER_SCHEMA.asStruct());

    assertUUIDsMatch(original, table);
    assertNoFiles(table);

    createOrReplace.commitTransaction();

    // validate the table after replace
    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();
    table.refresh(); // refresh should work with UUID validation

    Table loaded = catalog.loadTable(TABLE);

    assertThat(loaded.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(REPLACE_SCHEMA.asStruct());
    assertUUIDsMatch(original, loaded);
    assertFiles(loaded, FILE_A);
    assertPreviousMetadataFileCount(loaded, 1);
  }

  @Test
  public void testCompleteCreateOrReplaceTransactionReplace() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table original = catalog.buildTable(TABLE, OTHER_SCHEMA).create();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should exist before replaceTransaction")
        .isTrue();

    Map<String, String> properties =
        ImmutableMap.of("user", "someone", "created-at", "2022-02-25T00:38:19");
    Transaction createOrReplace =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withLocation(baseTableLocation(TABLE))
            .withPartitionSpec(SPEC)
            .withSortOrder(WRITE_ORDER)
            .withProperties(properties)
            .createOrReplaceTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should still exist after replaceTransaction")
        .isTrue();

    createOrReplace.newFastAppend().appendFile(FILE_A).commit();

    // validate table has not changed
    Table table = catalog.loadTable(TABLE);
    assertThat(table.schema().asStruct())
        .as("Table schema should match concurrent create")
        .isEqualTo(OTHER_SCHEMA.asStruct());
    assertThat(table.spec().isUnpartitioned()).as("Table should be unpartitioned").isTrue();
    assertThat(table.sortOrder().isUnsorted()).as("Table should be unsorted").isTrue();
    assertThat(table.properties().get("created-at"))
        .as("Created at should not match")
        .isNotEqualTo("2022-02-25T00:38:19");
    assertUUIDsMatch(original, table);
    assertNoFiles(table);

    createOrReplace.commitTransaction();

    // validate the table after replace
    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();
    table.refresh(); // refresh should work with UUID validation

    Table loaded = catalog.loadTable(TABLE);

    assertThat(loaded.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(REPLACE_SCHEMA.asStruct());
    assertThat(loaded.spec())
        .as("Table should have replace partition spec")
        .isEqualTo(REPLACE_SPEC);
    assertThat(loaded.sortOrder())
        .as("Table should have replace sort order")
        .isEqualTo(REPLACE_WRITE_ORDER);
    assertThat(loaded.properties().entrySet())
        .as("Table properties should be a superset of the requested properties")
        .containsAll(properties.entrySet());
    if (!overridesRequestedLocation()) {
      assertThat(table.location())
          .as("Table location should be replaced")
          .isEqualTo(baseTableLocation(TABLE));
    }

    assertUUIDsMatch(original, loaded);
    assertFiles(loaded, FILE_A);
    assertPreviousMetadataFileCount(loaded, 1);
  }

  @Test
  public void testCreateOrReplaceTransactionConcurrentCreate() {
    assumeThat(supportsServerSideRetry())
        .as("Conversion to replace transaction is not supported by REST catalog")
        .isTrue();

    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction createOrReplace = catalog.buildTable(TABLE, SCHEMA).createOrReplaceTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after createTransaction")
        .isFalse();

    createOrReplace.newFastAppend().appendFile(FILE_A).commit();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should not exist after append commit")
        .isFalse();

    catalog.buildTable(TABLE, OTHER_SCHEMA).create();

    String expectedMessage =
        supportsServerSideRetry()
            ? "Requirement failed: table already exists"
            : "Table already exists";
    assertThatThrownBy(createOrReplace::commitTransaction)
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageStartingWith(expectedMessage);

    // validate the concurrently created table is unmodified
    Table table = catalog.loadTable(TABLE);
    assertThat(table.schema().asStruct())
        .as("Table schema should match concurrent create")
        .isEqualTo(OTHER_SCHEMA.asStruct());
    assertNoFiles(table);
  }

  @Test
  public void testReplaceTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table original = catalog.buildTable(TABLE, OTHER_SCHEMA).create();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should exist before replaceTransaction")
        .isTrue();

    Transaction replace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should still exist after replaceTransaction")
        .isTrue();

    replace.newFastAppend().appendFile(FILE_A).commit();

    // validate table has not changed
    Table table = catalog.loadTable(TABLE);
    assertThat(table.schema().asStruct())
        .as("Table schema should match concurrent create")
        .isEqualTo(OTHER_SCHEMA.asStruct());
    assertUUIDsMatch(original, table);
    assertNoFiles(table);

    replace.commitTransaction();

    // validate the table after replace
    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();
    table.refresh(); // refresh should work with UUID validation

    Table loaded = catalog.loadTable(TABLE);

    assertThat(loaded.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(REPLACE_SCHEMA.asStruct());

    assertUUIDsMatch(original, loaded);
    assertFiles(loaded, FILE_A);
    assertPreviousMetadataFileCount(loaded, 1);
  }

  @Test
  public void testCompleteReplaceTransaction() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table original = catalog.buildTable(TABLE, OTHER_SCHEMA).create();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should exist before replaceTransaction")
        .isTrue();

    Map<String, String> properties =
        ImmutableMap.of("user", "someone", "created-at", "2022-02-25T00:38:19");
    Transaction replace =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withLocation(baseTableLocation(TABLE))
            .withPartitionSpec(SPEC)
            .withSortOrder(WRITE_ORDER)
            .withProperties(properties)
            .replaceTransaction();

    assertThat(catalog.tableExists(TABLE))
        .as("Table should still exist after replaceTransaction")
        .isTrue();

    replace.newFastAppend().appendFile(FILE_A).commit();

    // validate table has not changed
    Table table = catalog.loadTable(TABLE);

    assertThat(table.schema().asStruct())
        .as("Table schema should match concurrent create")
        .isEqualTo(OTHER_SCHEMA.asStruct());
    assertThat(table.spec().isUnpartitioned()).as("Table should be unpartitioned").isTrue();
    assertThat(table.sortOrder().isUnsorted()).as("Table should be unsorted").isTrue();
    assertThat(table.properties().get("created-at"))
        .as("Created at should not match")
        .isNotEqualTo("2022-02-25T00:38:19");

    assertUUIDsMatch(original, table);
    assertNoFiles(table);

    replace.commitTransaction();

    // validate the table after replace
    assertThat(catalog.tableExists(TABLE)).as("Table should exist after append commit").isTrue();
    table.refresh(); // refresh should work with UUID validation

    Table loaded = catalog.loadTable(TABLE);

    assertThat(loaded.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(REPLACE_SCHEMA.asStruct());
    assertThat(loaded.spec())
        .as("Table should have replace partition spec")
        .isEqualTo(REPLACE_SPEC);
    assertThat(loaded.sortOrder())
        .as("Table should have replace sort order")
        .isEqualTo(REPLACE_WRITE_ORDER);
    assertThat(loaded.properties().entrySet())
        .as("Table properties should be a superset of the requested properties")
        .containsAll(properties.entrySet());
    if (!overridesRequestedLocation()) {
      assertThat(table.location())
          .as("Table location should be replaced")
          .isEqualTo(baseTableLocation(TABLE));
    }

    assertUUIDsMatch(original, loaded);
    assertFiles(loaded, FILE_A);
    assertPreviousMetadataFileCount(loaded, 1);
  }

  @Test
  public void testReplaceTransactionRequiresTableExists() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    assertThatThrownBy(() -> catalog.buildTable(TABLE, SCHEMA).replaceTransaction())
        .isInstanceOf(NoSuchTableException.class)
        .hasMessageStartingWith("Table does not exist: %s", TABLE);
  }

  @Test
  public void testReplaceTableKeepsSnapshotLog() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TABLE.namespace());
    }

    catalog.createTable(TABLE, SCHEMA);

    Table table = catalog.loadTable(TABLE);
    table.newAppend().appendFile(FILE_A).commit();

    List<HistoryEntry> snapshotLogBeforeReplace =
        ((BaseTable) table).operations().current().snapshotLog();
    assertThat(snapshotLogBeforeReplace).hasSize(1);
    HistoryEntry snapshotBeforeReplace = snapshotLogBeforeReplace.get(0);

    Transaction replaceTableTransaction = catalog.newReplaceTableTransaction(TABLE, SCHEMA, false);
    replaceTableTransaction.newAppend().appendFile(FILE_A).commit();
    replaceTableTransaction.commitTransaction();
    table.refresh();

    List<HistoryEntry> snapshotLogAfterReplace =
        ((BaseTable) table).operations().current().snapshotLog();
    HistoryEntry snapshotAfterReplace = snapshotLogAfterReplace.get(1);

    assertThat(snapshotAfterReplace).isNotEqualTo(snapshotBeforeReplace);
    assertThat(snapshotLogAfterReplace)
        .hasSize(2)
        .containsExactly(snapshotBeforeReplace, snapshotAfterReplace);
  }

  @Test
  public void testConcurrentReplaceTransactions() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.schema().asStruct())
        .as("Table schema should match the original schema")
        .isEqualTo(original.schema().asStruct());
    assertThat(afterFirstReplace.spec().isUnpartitioned())
        .as("Table should be unpartitioned")
        .isTrue();
    assertThat(afterFirstReplace.sortOrder().isUnsorted()).as("Table should be unsorted").isTrue();
    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    secondReplace.commitTransaction();

    Table afterSecondReplace = catalog.loadTable(TABLE);
    assertThat(afterSecondReplace.schema().asStruct())
        .as("Table schema should match the original schema")
        .isEqualTo(original.schema().asStruct());
    assertThat(afterSecondReplace.spec().isUnpartitioned())
        .as("Table should be unpartitioned")
        .isTrue();
    assertThat(afterSecondReplace.sortOrder().isUnsorted()).as("Table should be unsorted").isTrue();
    assertUUIDsMatch(original, afterSecondReplace);
    assertFiles(afterSecondReplace, FILE_C);
  }

  @Test
  public void testConcurrentReplaceTransactionSchema() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, OTHER_SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace = catalog.buildTable(TABLE, OTHER_SCHEMA).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(REPLACE_SCHEMA.asStruct());
    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    secondReplace.commitTransaction();

    Table afterSecondReplace = catalog.loadTable(TABLE);
    assertThat(afterSecondReplace.schema().asStruct())
        .as("Table schema should match the original schema")
        .isEqualTo(original.schema().asStruct());
    assertUUIDsMatch(original, afterSecondReplace);
    assertFiles(afterSecondReplace, FILE_C);
  }

  @Test
  public void testConcurrentReplaceTransactionSchema2() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, OTHER_SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace = catalog.buildTable(TABLE, OTHER_SCHEMA).replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.schema().asStruct())
        .as("Table schema should match the original schema")
        .isEqualTo(original.schema().asStruct());
    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    secondReplace.commitTransaction();

    Table afterSecondReplace = catalog.loadTable(TABLE);
    assertThat(afterSecondReplace.schema().asStruct())
        .as("Table schema should match the new schema")
        .isEqualTo(REPLACE_SCHEMA.asStruct());
    assertUUIDsMatch(original, afterSecondReplace);
    assertFiles(afterSecondReplace, FILE_C);
  }

  @Test
  public void testConcurrentReplaceTransactionSchemaConflict() {
    assumeThat(supportsServerSideRetry()).as("Schema conflicts are detected server-side").isTrue();

    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, OTHER_SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.schema().asStruct())
        .as("Table schema should match the original schema")
        .isEqualTo(REPLACE_SCHEMA.asStruct());

    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    // even though the new schema is identical, the assertion that the last assigned id has not
    // changed will fail
    assertThatThrownBy(secondReplace::commitTransaction)
        .isInstanceOf(CommitFailedException.class)
        .hasMessageStartingWith(
            "Commit failed: Requirement failed: last assigned field id changed");
  }

  @Test
  public void testConcurrentReplaceTransactionPartitionSpec() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace =
        catalog.buildTable(TABLE, SCHEMA).withPartitionSpec(SPEC).replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.spec().fields())
        .as("Table spec should match the new spec")
        .isEqualTo(TABLE_SPEC.fields());
    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    secondReplace.commitTransaction();

    Table afterSecondReplace = catalog.loadTable(TABLE);
    assertThat(afterSecondReplace.spec().isUnpartitioned())
        .as("Table should be unpartitioned")
        .isTrue();
    assertUUIDsMatch(original, afterSecondReplace);
    assertFiles(afterSecondReplace, FILE_C);
  }

  @Test
  public void testConcurrentReplaceTransactionPartitionSpec2() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace =
        catalog.buildTable(TABLE, SCHEMA).withPartitionSpec(SPEC).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.spec().isUnpartitioned())
        .as("Table should be unpartitioned")
        .isTrue();
    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    secondReplace.commitTransaction();

    Table afterSecondReplace = catalog.loadTable(TABLE);
    assertThat(afterSecondReplace.spec().fields())
        .as("Table spec should match the new spec")
        .isEqualTo(TABLE_SPEC.fields());
    assertUUIDsMatch(original, afterSecondReplace);
    assertFiles(afterSecondReplace, FILE_C);
  }

  @Test
  public void testConcurrentReplaceTransactionPartitionSpecConflict() {
    assumeThat(supportsServerSideRetry()).as("Spec conflicts are detected server-side").isTrue();
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace =
        catalog.buildTable(TABLE, SCHEMA).withPartitionSpec(SPEC).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace =
        catalog.buildTable(TABLE, SCHEMA).withPartitionSpec(SPEC).replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.spec().fields())
        .as("Table spec should match the new spec")
        .isEqualTo(TABLE_SPEC.fields());
    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    // even though the new spec is identical, the assertion that the last assigned id has not
    // changed will fail
    assertThatThrownBy(secondReplace::commitTransaction)
        .isInstanceOf(CommitFailedException.class)
        .hasMessageStartingWith(
            "Commit failed: Requirement failed: last assigned partition id changed");
  }

  @Test
  public void testConcurrentReplaceTransactionSortOrder() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace = catalog.buildTable(TABLE, SCHEMA).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace =
        catalog.buildTable(TABLE, SCHEMA).withSortOrder(WRITE_ORDER).replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.sortOrder())
        .as("Table order should match the new order")
        .isEqualTo(TABLE_WRITE_ORDER);
    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    secondReplace.commitTransaction();

    Table afterSecondReplace = catalog.loadTable(TABLE);
    assertThat(afterSecondReplace.sortOrder().isUnsorted()).as("Table should be unsorted").isTrue();
    assertUUIDsMatch(original, afterSecondReplace);
    assertFiles(afterSecondReplace, FILE_C);
  }

  @Test
  public void testConcurrentReplaceTransactionSortOrderConflict() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Transaction transaction = catalog.buildTable(TABLE, SCHEMA).createTransaction();
    transaction.newFastAppend().appendFile(FILE_A).commit();
    transaction.commitTransaction();

    Table original = catalog.loadTable(TABLE);
    assertFiles(original, FILE_A);

    Transaction secondReplace =
        catalog.buildTable(TABLE, SCHEMA).withSortOrder(WRITE_ORDER).replaceTransaction();
    secondReplace.newFastAppend().appendFile(FILE_C).commit();

    Transaction firstReplace =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withSortOrder(
                SortOrder.builderFor(SCHEMA).desc(Expressions.bucket("id", 16)).desc("id").build())
            .replaceTransaction();
    firstReplace.newFastAppend().appendFile(FILE_B).commit();
    firstReplace.commitTransaction();

    Table afterFirstReplace = catalog.loadTable(TABLE);
    assertThat(afterFirstReplace.sortOrder().isSorted()).as("Table order should be set").isTrue();
    assertUUIDsMatch(original, afterFirstReplace);
    assertFiles(afterFirstReplace, FILE_B);

    secondReplace.commitTransaction();

    Table afterSecondReplace = catalog.loadTable(TABLE);
    assertThat(afterSecondReplace.sortOrder().fields())
        .as("Table order should match the new order")
        .isEqualTo(TABLE_WRITE_ORDER.fields());
    assertUUIDsMatch(original, afterSecondReplace);
    assertFiles(afterSecondReplace, FILE_C);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  public void createTableTransaction(int formatVersion) {
    if (requiresNamespaceCreate()) {
      catalog().createNamespace(NS);
    }

    catalog()
        .newCreateTableTransaction(
            TABLE,
            SCHEMA,
            PartitionSpec.unpartitioned(),
            ImmutableMap.of("format-version", String.valueOf(formatVersion)))
        .commitTransaction();

    assertThat(TableUtil.formatVersion(catalog().loadTable(TABLE))).isEqualTo(formatVersion);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  public void replaceTableTransaction(int formatVersion) {
    if (requiresNamespaceCreate()) {
      catalog().createNamespace(NS);
    }

    catalog()
        .newReplaceTableTransaction(
            TABLE,
            SCHEMA,
            PartitionSpec.unpartitioned(),
            ImmutableMap.of("format-version", String.valueOf(formatVersion)),
            true)
        .commitTransaction();

    BaseTable table = (BaseTable) catalog().loadTable(TABLE);
    assertThat(table.operations().current().formatVersion()).isEqualTo(formatVersion);
  }

  @Test
  public void testMetadataFileLocationsRemovalAfterCommit() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(NS);
    }

    Table table = catalog.buildTable(TABLE, SCHEMA).create();
    table.updateSchema().addColumn("a", Types.LongType.get()).commit();
    table.updateSchema().addColumn("b", Types.LongType.get()).commit();
    table.updateSchema().addColumn("c", Types.LongType.get()).commit();

    Set<String> metadataFileLocations = ReachableFileUtil.metadataFileLocations(table, false);
    assertThat(metadataFileLocations).hasSize(4);

    int maxPreviousVersionsToKeep = 2;
    table
        .updateProperties()
        .set(TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED, "true")
        .set(
            TableProperties.METADATA_PREVIOUS_VERSIONS_MAX,
            Integer.toString(maxPreviousVersionsToKeep))
        .commit();

    metadataFileLocations = ReachableFileUtil.metadataFileLocations(table, false);
    assertThat(metadataFileLocations).hasSize(maxPreviousVersionsToKeep + 1);

    // for each new commit, the amount of metadata files should stay the same and old files should
    // be deleted
    for (int i = 1; i <= 5; i++) {
      table.updateSchema().addColumn("d" + i, Types.LongType.get()).commit();
      metadataFileLocations = ReachableFileUtil.metadataFileLocations(table, false);
      assertThat(metadataFileLocations).hasSize(maxPreviousVersionsToKeep + 1);
    }

    maxPreviousVersionsToKeep = 4;
    table
        .updateProperties()
        .set(
            TableProperties.METADATA_PREVIOUS_VERSIONS_MAX,
            Integer.toString(maxPreviousVersionsToKeep))
        .commit();

    // for each new commit, the amount of metadata files should stay the same and old files should
    // be deleted
    for (int i = 1; i <= 10; i++) {
      table.updateSchema().addColumn("e" + i, Types.LongType.get()).commit();
      metadataFileLocations = ReachableFileUtil.metadataFileLocations(table, false);
      assertThat(metadataFileLocations).hasSize(maxPreviousVersionsToKeep + 1);
    }
  }

  @Test
  public void tableCreationWithoutNamespace() {
    assumeThat(requiresNamespaceCreate()).isTrue();

    assertThatThrownBy(
            () ->
                catalog().buildTable(TableIdentifier.of("non-existing", "table"), SCHEMA).create())
        .isInstanceOf(NoSuchNamespaceException.class)
        .hasMessageContaining("Namespace does not exist: non-existing");
  }

  @Test
  public void testRegisterTable() {
    C catalog = catalog();

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(TABLE.namespace());
    }

    Map<String, String> properties =
        ImmutableMap.of("user", "someone", "created-at", "2023-01-15T00:00:01");
    Table originalTable =
        catalog
            .buildTable(TABLE, SCHEMA)
            .withPartitionSpec(SPEC)
            .withSortOrder(WRITE_ORDER)
            .withProperties(properties)
            .create();

    originalTable.newFastAppend().appendFile(FILE_A).commit();
    originalTable.newFastAppend().appendFile(FILE_B).commit();
    originalTable.newDelete().deleteFile(FILE_A).commit();
    originalTable.newFastAppend().appendFile(FILE_C).commit();

    TableOperations ops = ((BaseTable) originalTable).operations();
    String metadataLocation = ops.current().metadataFileLocation();

    catalog.dropTable(TABLE, false /* do not purge */);

    Table registeredTable = catalog.registerTable(TABLE, metadataLocation);

    assertThat(registeredTable).isNotNull();
    assertThat(catalog.tableExists(TABLE)).as("Table must exist").isTrue();
    assertThat(registeredTable.properties())
        .as("Props must match")
        .containsAllEntriesOf(properties);
    assertThat(registeredTable.schema().asStruct())
        .as("Schema must match")
        .isEqualTo(originalTable.schema().asStruct());
    assertThat(registeredTable.specs()).as("Specs must match").isEqualTo(originalTable.specs());
    assertThat(registeredTable.sortOrders())
        .as("Sort orders must match")
        .isEqualTo(originalTable.sortOrders());
    assertThat(registeredTable.currentSnapshot())
        .as("Current snapshot must match")
        .isEqualTo(originalTable.currentSnapshot());
    assertThat(registeredTable.snapshots())
        .as("Snapshots must match")
        .isEqualTo(originalTable.snapshots());
    assertThat(registeredTable.history())
        .as("History must match")
        .isEqualTo(originalTable.history());

    TestHelpers.assertSameSchemaMap(registeredTable.schemas(), originalTable.schemas());
    assertFiles(registeredTable, FILE_B, FILE_C);

    registeredTable.newFastAppend().appendFile(FILE_A).commit();
    assertFiles(registeredTable, FILE_B, FILE_C, FILE_A);

    assertThat(catalog.loadTable(TABLE)).isNotNull();
    assertThat(catalog.dropTable(TABLE)).isTrue();
    assertThat(catalog.tableExists(TABLE)).isFalse();
  }

  @Test
  public void testRegisterExistingTable() {
    C catalog = catalog();

    TableIdentifier identifier = TableIdentifier.of("a", "t1");

    if (requiresNamespaceCreate()) {
      catalog.createNamespace(identifier.namespace());
    }

    catalog.createTable(identifier, SCHEMA);
    Table table = catalog.loadTable(identifier);
    TableOperations ops = ((BaseTable) table).operations();
    String metadataLocation = ops.current().metadataFileLocation();
    assertThatThrownBy(() -> catalog.registerTable(identifier, metadataLocation))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageStartingWith("Table already exists: a.t1");
    assertThat(catalog.dropTable(identifier)).isTrue();
  }

  @Test
  public void testCatalogWithCustomMetricsReporter() throws IOException {
    C catalogWithCustomReporter =
        initCatalog(
            "catalog_with_custom_reporter",
            ImmutableMap.of(
                CatalogProperties.METRICS_REPORTER_IMPL, CustomMetricsReporter.class.getName()));

    if (requiresNamespaceCreate()) {
      catalogWithCustomReporter.createNamespace(TABLE.namespace());
    }

    catalogWithCustomReporter.buildTable(TABLE, SCHEMA).create();

    Table table = catalogWithCustomReporter.loadTable(TABLE);
    DataFile dataFile =
        DataFiles.builder(PartitionSpec.unpartitioned())
            .withPath(FileFormat.PARQUET.addExtension(UUID.randomUUID().toString()))
            .withFileSizeInBytes(10)
            .withRecordCount(2)
            .build();

    // append file through FastAppend and check and reset counter
    table.newFastAppend().appendFile(dataFile).commit();
    assertThat(CustomMetricsReporter.COMMIT_COUNTER.get()).isEqualTo(1);
    CustomMetricsReporter.COMMIT_COUNTER.set(0);

    TableIdentifier identifier = TableIdentifier.of(NS, "custom_metrics_reporter_table");
    // append file through createTransaction() and check and reset counter
    catalogWithCustomReporter
        .buildTable(identifier, SCHEMA)
        .createTransaction()
        .newFastAppend()
        .appendFile(dataFile)
        .commit();
    assertThat(CustomMetricsReporter.COMMIT_COUNTER.get()).isEqualTo(1);
    CustomMetricsReporter.COMMIT_COUNTER.set(0);

    // append file through createOrReplaceTransaction() and check and reset counter
    catalogWithCustomReporter
        .buildTable(identifier, SCHEMA)
        .createOrReplaceTransaction()
        .newFastAppend()
        .appendFile(dataFile)
        .commit();
    assertThat(CustomMetricsReporter.COMMIT_COUNTER.get()).isEqualTo(1);
    CustomMetricsReporter.COMMIT_COUNTER.set(0);

    // append file through replaceTransaction() and check and reset counter
    catalogWithCustomReporter
        .buildTable(TABLE, SCHEMA)
        .replaceTransaction()
        .newFastAppend()
        .appendFile(dataFile)
        .commit();
    assertThat(CustomMetricsReporter.COMMIT_COUNTER.get()).isEqualTo(1);
    CustomMetricsReporter.COMMIT_COUNTER.set(0);

    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      assertThat(tasks.iterator()).hasNext();
    }

    assertThat(CustomMetricsReporter.SCAN_COUNTER.get()).isEqualTo(1);
    // reset counter in case subclasses run this test multiple times
    CustomMetricsReporter.SCAN_COUNTER.set(0);
  }

  public static class CustomMetricsReporter implements MetricsReporter {
    static final AtomicInteger SCAN_COUNTER = new AtomicInteger(0);
    static final AtomicInteger COMMIT_COUNTER = new AtomicInteger(0);

    @Override
    public void report(MetricsReport report) {
      if (report instanceof ScanReport) {
        SCAN_COUNTER.incrementAndGet();
      } else if (report instanceof CommitReport) {
        COMMIT_COUNTER.incrementAndGet();
      }
    }
  }

  private static void assertEmpty(String context, Catalog catalog, Namespace ns) {
    try {
      assertThat(catalog.listTables(ns)).as(context).isEmpty();
    } catch (NoSuchNamespaceException e) {
      // it is okay if the catalog throws NoSuchNamespaceException when it is empty
    }
  }

  public void assertUUIDsMatch(Table expected, Table actual) {
    assertThat(((BaseTable) actual).operations().current().uuid())
        .as("Table UUID should not change")
        .isEqualTo(((BaseTable) expected).operations().current().uuid());
  }

  public void assertPreviousMetadataFileCount(Table table, int metadataFileCount) {
    TableOperations ops = ((BaseTable) table).operations();
    assertThat(ops.current().previousFiles())
        .as("Table should have correct number of previous metadata locations")
        .hasSize(metadataFileCount);
  }

  public void assertNoFiles(Table table) {
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      assertThat(tasks).as("Should contain no files").isEmpty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void assertFiles(Table table, DataFile... files) {
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      List<CharSequence> paths =
          Streams.stream(tasks)
              .map(FileScanTask::file)
              .map(DataFile::location)
              .collect(Collectors.toList());
      assertThat(paths).as("Should contain expected number of data files").hasSize(files.length);
      assertThat(CharSequenceSet.of(paths))
          .as("Should contain correct file paths")
          .isEqualTo(
              CharSequenceSet.of(Iterables.transform(Arrays.asList(files), DataFile::location)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void assertFilePartitionSpec(Table table, DataFile dataFile, int specId) {
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      Streams.stream(tasks)
          .map(FileScanTask::file)
          .filter(file -> file.location().equals(dataFile.location()))
          .forEach(file -> assertThat(file.specId()).as("Spec ID should match").isEqualTo(specId));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void assertFilesPartitionSpec(Table table) {
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      Streams.stream(tasks)
          .map(FileScanTask::file)
          .forEach(
              file ->
                  assertThat(file.specId())
                      .as("Spec ID should match")
                      .isEqualTo(table.spec().specId()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<Namespace> concat(List<Namespace> starting, Namespace... additional) {
    List<Namespace> namespaces = Lists.newArrayList();
    namespaces.addAll(starting);
    namespaces.addAll(Arrays.asList(additional));
    return namespaces;
  }
}
