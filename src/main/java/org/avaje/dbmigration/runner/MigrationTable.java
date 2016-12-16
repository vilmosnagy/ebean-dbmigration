package org.avaje.dbmigration.runner;

import com.avaje.ebean.config.Platform;
import org.avaje.dbmigration.MigrationConfig;
import org.avaje.dbmigration.util.IOUtils;
import org.avaje.dbmigration.util.JdbcClose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.sql.*;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the migration table.
 */
public class MigrationTable {

  private static final Logger logger = LoggerFactory.getLogger(MigrationTable.class);

  private final Connection connection;

  private final String catalog;
  private final String schema;
  private final String table;
  private final String envUserName;

  private final Timestamp runOn = new Timestamp(System.currentTimeMillis());

  private final ScriptTransform scriptTransform;

  private final String insertSql;
  private final String selectSql;

  private final LinkedHashMap<String, MigrationMetaRow> migrations;
  private final MigrationConfig config;

  private MigrationMetaRow lastMigration;

  /**
   * Construct with server, configuration and jdbc connection (DB admin user).
   */
  public MigrationTable(MigrationConfig config, Connection connection) {

    this.connection = connection;
    this.migrations = new LinkedHashMap<String, MigrationMetaRow>();

    this.catalog = null;
    this.config = config;
    this.schema = config.getDbSchema();
    this.table = config.getMetaTable();
    this.selectSql = MigrationMetaRow.selectSql(config, table);
    this.insertSql = MigrationMetaRow.insertSql(table);
    this.scriptTransform = createScriptTransform(config);
    this.envUserName = System.getProperty("user.name");
  }

  /**
   * Return the number of migrations in the DB migration table.
   */
  public int size() {
    return migrations.size();
  }

  /**
   * Create the ScriptTransform for placeholder key/value replacement.
   */
  private ScriptTransform createScriptTransform(MigrationConfig config) {

    Map<String, String> map = PlaceholderBuilder.build(config.getRunPlaceholders(), config.getRunPlaceholderMap());
    return new ScriptTransform(map);
  }

  /**
   * Create the table is it does not exist.
   */
  public void createIfNeeded() throws SQLException, IOException {

    if (!tableExists(connection)) {
      createTable(connection, config.getPlatform());
    }

    PreparedStatement query = connection.prepareStatement(selectSql);
    try {
      ResultSet resultSet = query.executeQuery();
      try {
        while (resultSet.next()) {
          MigrationMetaRow metaRow = new MigrationMetaRow(resultSet);
          addMigration(metaRow.getVersion(), metaRow);
        }
      } finally {
        JdbcClose.close(resultSet);
      }
    } finally {
      JdbcClose.close(query);
    }
  }


  private void createTable(Connection connection, Platform platform) throws IOException, SQLException {

    String script = ScriptTransform.table(table, getCreateTableScript(platform));

    MigrationScriptRunner run = new MigrationScriptRunner(connection);
    run.runScript(false, script, "create migration table");
  }

  /**
   * Return the create table script.
   * @param platform
   */
  private String getCreateTableScript(Platform platform) throws IOException {
    // supply a script to override the default table create script
    String script = readResource("migration-support/create-table.sql");
    if (script == null) {
      // no, just use the default script
      switch (platform) {
        case SQLSERVER:
          return readResource("migration-support/sql-server-create-table.sql");
        default:
          return readResource("migration-support/default-create-table.sql");
      }
    }
    return script;
  }

  private String readResource(String location) throws IOException {

    Enumeration<URL> resources = getClassLoader().getResources(location);
    if (resources.hasMoreElements()) {
      URL url = resources.nextElement();
      return IOUtils.readUtf8(url.openStream());
    }
    return null;
  }

  private ClassLoader getClassLoader() {
    return Thread.currentThread().getContextClassLoader();
  }

  /**
   * Return true if the table exists.
   */
  private boolean tableExists(Connection connection) throws SQLException {

    String migTable = table;

    DatabaseMetaData metaData = connection.getMetaData();
    if (metaData.storesUpperCaseIdentifiers()) {
      migTable = migTable.toUpperCase();
    }
    ResultSet tables = metaData.getTables(catalog, schema, migTable, null);
    try {
      return tables.next();
    } finally {
      JdbcClose.close(tables);
    }
  }

  /**
   * Return true if the migration ran successfully and false if the migration failed.
   */
  public boolean shouldRun(LocalMigrationResource localVersion, LocalMigrationResource priorVersion) throws SQLException {

    if (priorVersion != null && !localVersion.isRepeatable()) {
      if (!migrationExists(priorVersion)) {
        logger.error("Migration {} requires prior migration {} which has not been run", localVersion.getVersion(), priorVersion.getVersion());
        return false;
      }
    }

    MigrationMetaRow existing = migrations.get(localVersion.key());
    return runMigration(localVersion, existing);
  }

  /**
   * Run the migration script.
   *
   * @param local    The local migration resource
   * @param existing The information for this migration existing in the table
   *
   * @return True if the migrations should continue
   */
  private boolean runMigration(LocalMigrationResource local, MigrationMetaRow existing) throws SQLException {

    String script = convertScript(local.getContent());
    int checksum = Checksum.calculate(script);

    if (existing != null) {

      boolean matchChecksum = (existing.getChecksum() == checksum);

      if (!local.isRepeatable()) {
        if (!matchChecksum) {
          logger.error("Checksum mismatch on migration {}", local.getLocation());
        }
        return true;

      } else if (matchChecksum) {
        logger.trace("... skip unchanged repeatable migration {}", local.getLocation());
        return true;
      }
    }

    runMigration(local, script, checksum);
    return true;
  }

  /**
   * Run a migration script as new migration or update on existing repeatable migration.
   */
  private void runMigration(LocalMigrationResource local, String script, int checksum) throws SQLException {

    logger.debug("run migration {}", local.getLocation());

    long start = System.currentTimeMillis();
    MigrationScriptRunner run = new MigrationScriptRunner(connection);
    run.runScript(false, script, "run migration version: " + local.getVersion());

    long exeMillis = System.currentTimeMillis() - start;

    MigrationMetaRow metaRow = createMetaRow(local, checksum, exeMillis);
    PreparedStatement statement = connection.prepareStatement(insertSql);
    try {
      metaRow.bindInsert(statement);
      statement.executeUpdate();
      addMigration(local.key(), metaRow);
    } finally {
      JdbcClose.close(statement);
    }
  }

  /**
   * Create the MigrationMetaRow for this migration.
   */
  private MigrationMetaRow createMetaRow(LocalMigrationResource migration, int checksum, long exeMillis) {

    int nextId = 1;
    if (lastMigration != null) {
      nextId = lastMigration.getId() + 1;
    }

    String type = migration.getType();
    String runVersion = migration.key();
    String comment = migration.getComment();

    return new MigrationMetaRow(nextId, type, runVersion, comment, checksum, envUserName, runOn, exeMillis);
  }

  /**
   * Return true if the migration exists.
   */
  private boolean migrationExists(LocalMigrationResource priorVersion) {
    return migrations.containsKey(priorVersion.key());
  }

  /**
   * Apply the placeholder key/value replacement on the script.
   */
  private String convertScript(String script) {
    return scriptTransform.transform(script);
  }

  /**
   * Register the successfully executed migration (to allow dependant scripts to run).
   */
  private void addMigration(String key, MigrationMetaRow metaRow) {
    lastMigration = metaRow;
    if (metaRow.getVersion() == null) {
      throw new IllegalStateException("No runVersion in db migration table row? " + metaRow);
    }
    migrations.put(key, metaRow);
  }
}
