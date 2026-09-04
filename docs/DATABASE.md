# PostgreSQL

Shed has an explicit PostgreSQL CLI bridge for a user-installed `psql`. It is a narrow local-workbench feature, not a general database explorer or connection manager.

```text
:database status
:database query "select current_database()"
:database tables
:database file migrations/check.sql
:database terminal
```

`status` is local-only: it opens no connection and reports only whether non-secret libpq selectors such as `PGHOST`, `PGDATABASE`, `PGUSER`, `PGSERVICE`, or `PGPASSFILE` are present. It never displays a connection string, password, or `PGPASSWORD` state. Query, table, and file operations run only after their explicit command, using direct argv equivalent to:

```text
psql --no-psqlrc --set ON_ERROR_STOP=on --command <sql>
```

`--no-psqlrc` keeps arbitrary psql startup commands out of Shed-launched jobs. libpq still owns normal connection setup, including a user-managed service file, password file, SSL configuration, and environment variables. Shed neither stores credentials nor injects them into a command line. PostgreSQL advises using a password file rather than `PGPASSWORD`, which may be visible to other local processes on some systems. [PostgreSQL libpq environment variables](https://www.postgresql.org/docs/current/libpq-envars.html)

SQL files must be existing `.sql` files inside the active workspace, including after symbolic-link resolution. Their contents are intentionally not parsed or restricted: running a query or file is an explicit database action with the authenticated database role's permissions. `tables` uses a fixed `information_schema` query. `terminal` opens interactive `psql` in Shed's terminal and leaves subsequent commands to the user.

Shed does not include JDBC drivers, connection persistence, credential storage, schema diff/migration UI, result editing, query plans, database-specific language services, SQLite/MySQL/MSSQL/Oracle support, cloud-database auth, or collaborative database sessions. Use a Java extension integration for another database provider.
