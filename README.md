# MyApp Backup System

MongoDB backup service with a staging test stand and a Jenkins server configured as code (JCasC).

## Test stand

The test stand runs on Docker Compose and consists of three services connected
by the `jenkins-backup-network` bridge network:

| Service | Image | Purpose | Host port |
|---|---|---|---|
| `jenkins-server` | `myapp-jenkins:latest` (built from `test-stand/jenkins-infra/`) | Jenkins with JCasC, runs backup jobs; includes `mongodump` / `mongorestore` | `8080`, `50000` |
| `mongodb-test` | `mongo:3.6` | Staging database, seeded from `test-stand/init-db/` on first start | `27017` |
| `backup-storage` | `myapp-backup-storage:latest` (built from `test-stand/backup-storage/`) | Backup storage node: NFS server (NFSv4.1) that exports the backup disk; no HTTP | — (internal only, `2049`) |

Backup disk: the volume `backup_disk` on `backup-storage`, exported over NFS.
Jenkins mounts the export as the volume `backup_nfs` at

- `/var/backups/mongodb` (read-write, the jobs write backups here)
- `/var/jenkins_home/userContent/backups` (read-only, served by Jenkins for downloads)

The Docker daemon mounts `backup_nfs` by IP, so `backup-storage` has a fixed address
(`BACKUP_STORAGE_IP`, default `172.28.10.10`, in the subnet `BACKUP_NETWORK_SUBNET`, default `172.28.10.0/24`).
`backup-storage` runs privileged: the kernel NFS server needs it.
See [docs/architecture.md](docs/architecture.md) for the full picture.

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
- Backup disk mounted over NFS:

  ```bash
  docker exec jenkins-server findmnt /var/backups/mongodb
  docker exec jenkins-server ls -l /var/backups/mongodb
  ```

- Manual backup test (dump `myapp` to the shared disk):

  ```bash
  docker exec jenkins-server mongodump \
    --uri 'mongodb://root:mongo123@mongodb-test:27017/?authSource=admin' \
    --db myapp --gzip --archive=/var/backups/mongodb/manual-test.archive.gz
  ```

### Backup pipeline

The `myapp-backup` job is created by JCasC from the Job DSL script
[`test-stand/jenkins-infra/jobs/myapp-backup.groovy`](test-stand/jenkins-infra/jobs/myapp-backup.groovy)
(parameters and their defaults). It runs [`.jenkins/Jenkinsfile.backup`](.jenkins/Jenkinsfile.backup)
from `BACKUP_REPO_URL`, so push changes before running it.

The build form shows only `ACTION`, the backup table, `SELECTED_BACKUP` and the download log checkbox.
The job settings (MongoDB connection, `TARGET_DB`, `BACKUP_DIR`, retention, storage checks, timeout) are hidden parameters.
They are visible on the job **Configure** page, and their values come from `SETTINGS` in the Job DSL script.
Edits made on the Configure page are lost on the next Jenkins start.

Use **Build with Parameters**:

| `ACTION` | What it does |
|---|---|
| `create` | `mongodump` of `MONGO_DB` into `BACKUP_DIR` (`.archive.gz` + `.sha256`), then rotation if `AUTO_ROTATE_AFTER_DUMP` |
| `restore` | Verifies the checksum and restores `SELECTED_BACKUP` into `TARGET_DB` (collections are dropped first) |
| `delete` | Removes `SELECTED_BACKUP` and its `.sha256` |
| `rotate` | Removes backups older than `RETENTION_DAYS` (the newest backup is always kept) |

Before every action the pipeline runs `backup-core/scripts/check-storage.sh`:

| Setting | Default | Check |
|---|---|---|
| `BACKUP_MOUNT` | `/var/backups/mongodb` | The backup disk answers within 30 s, is mounted there, and `BACKUP_DIR` is on it; empty disables the check |
| `MIN_FREE_SPACE_GB` | `10` | Free space in `BACKUP_DIR` before `create`; `0` disables the check |

`PIPELINE_TIMEOUT_HOURS` (default `2`) is the build timeout; raise it for large databases.

`BACKUPS_INFO_TABLE` shows the backups in `BACKUP_DIR` with download links, and `SELECTED_BACKUP` lists them newest first.
The links point to `/userContent/backups/`: Jenkins serves the shared disk there (read-only, login required).
Downloads are recorded by the Audit Trail plugin in `/var/jenkins_home/audit/` (user, time, IP).
Check `SHOW_DOWNLOAD_LOG` on the build form to see the log.
MongoDB credentials come from the Jenkins credential `mongo-backup-credentials`, which JCasC creates from `MONGO_ROOT_*`.

### Logs

```bash
docker compose logs -f jenkins-server
```

### Stop

```bash
docker compose down      # keep Jenkins, MongoDB and backup data
docker compose down -v   # also delete volumes (backups included)
```

After `down -v`, MongoDB loads the seed data from `init-db/seed-data.js` again on the next start.
