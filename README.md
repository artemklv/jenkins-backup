# MyApp Backup System

MongoDB backup service with a staging test stand and a Jenkins server configured as code (JCasC).

## Test stand

The test stand runs on Docker Compose and consists of three services connected
by the `jenkins-backup-network` bridge network:

| Service | Image | Purpose | Host port |
|---|---|---|---|
| `jenkins-server` | `myapp-jenkins:latest` (built from `test-stand/jenkins-infra/`) | Jenkins with JCasC, runs backup jobs; includes `mongodump` / `mongorestore` | `8080`, `50000` |
| `mongodb-test` | `mongo:3.6` | Staging database, seeded from `test-stand/init-db/` on first start | `27017` |
| `backup-storage` | `myapp-backup-storage:latest` (built from `test-stand/backup-storage/`) | Backup storage node, read-only HTTP listing of backups | — (internal only) |

Shared backup disk: the host folder `test-stand/mock-storage/` is mounted into

- `jenkins-server` at `/var/backups/mongodb` (read-write, Jenkins writes backups here, one subdirectory per environment)
- `jenkins-server` at `/var/jenkins_home/userContent/backups` (read-only, Jenkins serves it at `/userContent/backups/` for download links)
- `backup-storage` at `/srv/backups` (read-only)

### Prerequisites

- Docker with the Compose plugin (`docker compose`)

### Configuration

All variables for the test run are in [`test-stand/.env`](test-stand/.env) (test-only credentials).
Docker Compose loads this file automatically.
To use a fork or another repository, change `BACKUP_REPO_URL` (and `BACKUP_REPO_BRANCH`) there.

### Start

```bash
cd test-stand
```

```bash
docker compose up -d --build --wait
```

`--wait` returns once all services are healthy.

### Check

```bash
docker compose ps
```

- Jenkins UI: <http://localhost:8080>. Log in as `admin` / `admin123` or `operator` / `operator123`.
- MongoDB: `mongodb://root:mongo123@localhost:27017/?authSource=admin`
- Backup listing (from inside the network):

  ```bash
  docker exec jenkins-server curl -s http://backup-storage/backups/
  ```

- Manual backup test (dump `myapp` to the shared disk):

  ```bash
  docker exec jenkins-server mongodump \
    --uri 'mongodb://root:mongo123@mongodb-test:27017/?authSource=admin' \
    --db myapp --gzip --archive=/var/backups/mongodb/test/manual-test.archive.gz
  ```

### Backup jobs

Jobs are created by JCasC from the Job DSL script
[`test-stand/jenkins-infra/jobs/backup.groovy`](test-stand/jenkins-infra/jobs/backup.groovy)
and run the `.jenkins/Jenkinsfile.*` pipelines from `BACKUP_REPO_URL`, so push changes before running them.

```
backup/                      top folder
└── test/                    environment: settings are folder properties
    ├── create               mongodump + rotation; daily and on demand
    ├── restore              restore the selected backup into TARGET_DB
    ├── delete               delete the selected backup
    └── list                 "Backups" report: backups, download links, download log
```

- **Environment settings** (`MONGO_*`, `TARGET_DB`, `RETENTION_DAYS`, `AUTO_ROTATE_AFTER_DUMP`)
  are in `ENVIRONMENTS` in the Job DSL script and become properties of the environment folder.
  The pipelines read them with `withFolderProperties()`. No secrets there:
  `MONGO_CREDENTIALS_ID` only references a Jenkins credential.
- **Backups** of an environment are stored in `/var/backups/mongodb/<environment>/`
  (`.archive.gz` + `.sha256`). Rotation keeps the newest backup even if it is older than `RETENTION_DAYS`.
- **restore / delete** show the backup table and a `SELECTED_BACKUP` list on the build form.
- **Downloads**: open `list` → **Backups**, or use the links in the restore / delete table.
  Jenkins serves the files at `/userContent/backups/<environment>/` (read-only, login required).
  The Audit Trail plugin logs every download (user, time, IP) to `/var/jenkins_home/audit/`,
  and the report shows that log. `list` is refreshed after create / delete and every 15 minutes.
- To add an environment, add an entry to `ENVIRONMENTS` and rebuild the Jenkins image.

### Logs

```bash
docker compose logs -f jenkins-server
```

### Stop

```bash
docker compose down      # keep Jenkins and MongoDB data
docker compose down -v   # also delete volumes
```

After `down -v`, MongoDB loads the seed data from `init-db/seed-data.js` again on the next start.
