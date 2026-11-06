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

- `jenkins-server` at `/var/backups/mongodb` (read-write, Jenkins writes backups here)
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
    --db myapp --gzip --archive=/var/backups/mongodb/manual-test.archive.gz
  ```

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
