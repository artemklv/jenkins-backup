// Job DSL: folder tree for MongoDB backups (loaded by jenkins-casc.yaml).
//
//   backup/                      top folder
//   backup/<environment>/        environment settings as folder properties
//   backup/<environment>/create  dump + rotation, on a schedule and on demand
//   backup/<environment>/restore restore the selected backup into TARGET_DB
//   backup/<environment>/delete  delete the selected backup
//   backup/<environment>/list    "Backups" HTML report: backups, download links, download log
//
// Jobs are regenerated on every Jenkins start: edits made in the UI are lost.
// The pipelines are the .jenkins/Jenkinsfile.* files from BACKUP_REPO_URL.

def sysEnv = System.getenv()
def REPO_URL = sysEnv.BACKUP_REPO_URL ?: 'https://github.com/artemklv/jenkins-backup.git'
def REPO_BRANCH = sysEnv.BACKUP_REPO_BRANCH ?: 'main'

// Shared backup disk: each environment uses its own subdirectory.
// Jenkins serves BACKUP_ROOT read-only at DOWNLOAD_ROOT (see docker-compose.yml).
def BACKUP_ROOT = '/var/backups/mongodb'
def DOWNLOAD_ROOT = '/userContent/backups'

// Environments: folder backup/<name>. 'settings' become folder properties,
// which the pipelines read as environment variables (withFolderProperties).
// Secrets are not stored here: MONGO_CREDENTIALS_ID only references a credential.
def ENVIRONMENTS = [
    test: [
        description   : 'Test environment: MongoDB 3.6 on the test stand',
        createSchedule: 'H 2 * * *',
        settings      : [
            MONGO_HOST            : 'mongodb-test',
            MONGO_PORT            : '27017',
            MONGO_DB              : 'myapp',
            MONGO_AUTH_SOURCE     : 'admin',
            MONGO_CREDENTIALS_ID  : 'mongo-backup-credentials',
            TARGET_DB             : 'myapp_restore',
            RETENTION_DAYS        : '7',
            AUTO_ROTATE_AFTER_DUMP: 'true',
        ],
    ],
]

// Active Choices scripts run on the controller in the Groovy sandbox each time
// the build form is opened. They cannot read folder properties, so the backup
// directory and download URL are substituted here (@BACKUP_DIR@, @DOWNLOAD_URL@).
// The java.io.File calls they use are approved in jenkins-casc.yaml (security.scriptApproval).
def BACKUPS_TABLE_SCRIPT = '''
def dir = new File('@BACKUP_DIR@')
if (!dir.isDirectory()) {
    return "<p>No backups yet: <code>${dir.path}</code> does not exist.</p>"
}
def files = (dir.listFiles() ?: []).findAll { it.isFile() && it.name.endsWith('.archive.gz') }
if (files.isEmpty()) {
    return "<p>No backups found in <code>${dir.path}</code>.</p>"
}
files = files.sort { a, b -> b.lastModified() <=> a.lastModified() }
def esc = { String s -> s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }
def fmt = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss')
def downloadLink = { String name ->
    (name ==~ /[A-Za-z0-9._-]+/) ? '<a href="@DOWNLOAD_URL@/' + name + '">download</a>' : '-'
}
// SHA256 from the '<name>.sha256' file (first field of the sha256sum output)
def checksumOf = { String name ->
    def sumFile = new File(dir, name + '.sha256')
    if (!sumFile.isFile()) {
        return 'missing'
    }
    def hash = sumFile.text.trim().tokenize(' ')[0] ?: ''
    return (hash ==~ /[0-9a-f]{64}/) ? '<code>' + hash + '</code>' : 'invalid'
}
// Only inline styles survive the HTML sanitizer, so borders are set per cell
def cell = 'style="border:1px solid #999;padding:4px 8px"'
def html = []
html.add('<table style="border-collapse:collapse">')
html.add('<tr>' + ['#', 'File', 'Size, MB', 'Modified', 'SHA256', 'Download'].collect { "<th ${cell}>${it}</th>" }.join('') + '</tr>')
files.eachWithIndex { f, i ->
    def sizeMb = String.format('%.2f', f.length() / 1048576.0d)
    def values = [i + 1, esc(f.name), sizeMb, fmt.format(new Date(f.lastModified())), checksumOf(f.name), downloadLink(f.name)]
    html.add('<tr>' + values.collect { "<td ${cell}>${it}</td>" }.join('') + '</tr>')
}
html.add('</table>')
html.add("<p>Total: ${files.size()} backup(s) in <code>${dir.path}</code></p>")
return html.join('\\n')
'''

def BACKUP_FILES_SCRIPT = '''
def dir = new File('@BACKUP_DIR@')
if (!dir.isDirectory()) {
    return []
}
return (dir.listFiles() ?: [])
    .findAll { it.isFile() && it.name.endsWith('.archive.gz') }
    .sort { a, b -> b.lastModified() <=> a.lastModified() }
    .collect { it.name }
'''

// Pipeline definition: a Jenkinsfile from the repository
def fromRepo = { String path ->
    return {
        cpsScm {
            scm {
                git {
                    remote {
                        url(REPO_URL)
                        // credentials('git-credentials') // for a private repository
                    }
                    branch(REPO_BRANCH)
                }
            }
            scriptPath(path)
            lightweight(true)
        }
    }
}

// Build form of restore / delete: backup table + file list
def backupSelection = { String backupDir, String downloadUrl, String verb ->
    return {
        activeChoiceHtml {
            name('BACKUPS_INFO_TABLE')
            description('Backups of this environment')
            randomName('choice-parameter-backups-info-table')
            choiceType('ET_FORMATTED_HTML')
            omitValueField(true)
            referencedParameters('')
            script {
                groovyScript {
                    script {
                        script(BACKUPS_TABLE_SCRIPT.replace('@BACKUP_DIR@', backupDir).replace('@DOWNLOAD_URL@', downloadUrl))
                        sandbox(true)
                    }
                    fallbackScript {
                        script('return "<p>Failed to list backups (check In-process Script Approval)</p>"')
                        sandbox(true)
                    }
                }
            }
        }
        reactiveChoice {
            name('SELECTED_BACKUP')
            description("Backup to ${verb} (newest first)")
            randomName('choice-parameter-selected-backup')
            choiceType('PT_SINGLE_SELECT')
            filterable(false)
            filterLength(1)
            referencedParameters('')
            script {
                groovyScript {
                    script {
                        script(BACKUP_FILES_SCRIPT.replace('@BACKUP_DIR@', backupDir))
                        sandbox(true)
                    }
                    fallbackScript {
                        script('return []')
                        sandbox(true)
                    }
                }
            }
        }
    }
}

folder('backup') {
    displayName('Backup')
    description('MongoDB backups by environment')
}

ENVIRONMENTS.each { envName, cfg ->
    def base = "backup/${envName}"
    def backupDir = "${BACKUP_ROOT}/${envName}"
    def downloadUrl = "${DOWNLOAD_ROOT}/${envName}"
    def folderSettings = cfg.settings + [
        ENV_NAME    : envName,
        BACKUP_DIR  : backupDir,
        DOWNLOAD_URL: downloadUrl,
    ]

    folder(base) {
        description(cfg.description)
        properties {
            folderProperties {
                properties {
                    folderSettings.each { settingName, settingValue ->
                        stringProperty {
                            key(settingName)
                            value(settingValue)
                        }
                    }
                }
            }
        }
    }

    pipelineJob("${base}/create") {
        description("Create a backup of ${cfg.settings.MONGO_DB}, then remove backups older than RETENTION_DAYS")
        logRotator { numToKeep(30) }
        properties {
            disableConcurrentBuilds()
            pipelineTriggers {
                triggers {
                    cron { spec(cfg.createSchedule) }
                }
            }
        }
        definition(fromRepo('.jenkins/Jenkinsfile.create'))
    }

    pipelineJob("${base}/restore") {
        description("Restore the selected backup into ${cfg.settings.TARGET_DB} (its collections are replaced)")
        logRotator { numToKeep(30) }
        properties {
            disableConcurrentBuilds()
            parameters { parameterDefinitions(backupSelection(backupDir, downloadUrl, 'restore')) }
        }
        definition(fromRepo('.jenkins/Jenkinsfile.restore'))
    }

    pipelineJob("${base}/delete") {
        description('Delete the selected backup and its checksum file')
        logRotator { numToKeep(30) }
        properties {
            disableConcurrentBuilds()
            parameters { parameterDefinitions(backupSelection(backupDir, downloadUrl, 'delete')) }
        }
        definition(fromRepo('.jenkins/Jenkinsfile.delete'))
    }

    pipelineJob("${base}/list") {
        description('Backups of this environment with download links and the download log: open "Backups" in the menu')
        logRotator { numToKeep(10) }
        properties {
            disableConcurrentBuilds()
            pipelineTriggers {
                triggers {
                    // Refresh the download log; create / delete trigger this job as well
                    cron { spec('H/15 * * * *') }
                }
            }
        }
        definition(fromRepo('.jenkins/Jenkinsfile.dashboard'))
    }
}
