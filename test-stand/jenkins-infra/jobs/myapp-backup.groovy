// Job DSL definition of the 'myapp-backup' job (loaded by jenkins-casc.yaml).
//
// All job parameters live here, so the job is fully configured on every Jenkins
// start and the parameter form works before the first build.
// The pipeline itself is .jenkins/Jenkinsfile.backup from BACKUP_REPO_URL.

def env = System.getenv()

// Default settings, overridable per build through the job parameters
def DEFAULTS = [
    MONGO_HOST            : 'mongodb-test',
    MONGO_PORT            : '27017',
    MONGO_DB              : 'myapp',
    TARGET_DB             : 'myapp_restore',
    MONGO_AUTH_SOURCE     : 'admin',
    MONGO_CREDENTIALS_ID  : 'mongo-backup-credentials',
    BACKUP_DIR            : '/var/backups/mongodb',
    RETENTION_DAYS        : '7',
    AUTO_ROTATE_AFTER_DUMP: true,
]

// Active Choices scripts run on the controller in the Groovy sandbox each time
// the form is opened. The java.io.File calls they use are approved in
// jenkins-casc.yaml (security.scriptApproval).
def BACKUPS_TABLE_SCRIPT = '''
// BACKUP_DIR is not bound when the form is first rendered on the server
def backupDir = null
try {
    backupDir = BACKUP_DIR
} catch (MissingPropertyException ignored) {
}
def dir = new File(backupDir ?: '/var/backups/mongodb')
if (!dir.isDirectory()) {
    return "<p><b>Backup directory not found:</b> ${dir.path}</p>"
}
def files = (dir.listFiles() ?: []).findAll { it.isFile() && it.name.endsWith('.archive.gz') }
if (files.isEmpty()) {
    return "<p>No backups found in <code>${dir.path}</code>.</p>"
}
files = files.sort { a, b -> b.lastModified() <=> a.lastModified() }
def esc = { String s -> s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }
def fmt = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss')
// Only inline styles survive the HTML sanitizer, so borders are set per cell
def cell = 'style="border:1px solid #999;padding:4px 8px"'
def html = []
html.add('<table style="border-collapse:collapse">')
html.add('<tr>' + ['#', 'File', 'Size, MB', 'Modified', 'SHA256'].collect { "<th ${cell}>${it}</th>" }.join('') + '</tr>')
files.eachWithIndex { f, i ->
    def sizeMb = String.format('%.2f', f.length() / 1048576.0d)
    def checksum = new File(dir, f.name + '.sha256').exists() ? 'yes' : 'missing'
    def values = [i + 1, esc(f.name), sizeMb, fmt.format(new Date(f.lastModified())), checksum]
    html.add('<tr>' + values.collect { "<td ${cell}>${it}</td>" }.join('') + '</tr>')
}
html.add('</table>')
html.add("<p>Total: ${files.size()} backup(s) in <code>${dir.path}</code></p>")
return html.join('\\n')
'''

def BACKUP_FILES_SCRIPT = '''
// BACKUP_DIR is not bound when the form is first rendered on the server
def backupDir = null
try {
    backupDir = BACKUP_DIR
} catch (MissingPropertyException ignored) {
}
def dir = new File(backupDir ?: '/var/backups/mongodb')
if (!dir.isDirectory()) {
    return []
}
return (dir.listFiles() ?: [])
    .findAll { it.isFile() && it.name.endsWith('.archive.gz') }
    .sort { a, b -> b.lastModified() <=> a.lastModified() }
    .collect { it.name }
'''

pipelineJob('myapp-backup') {
    description('MongoDB backups: create, download, restore, delete, rotate (.jenkins/Jenkinsfile.backup)')

    logRotator {
        numToKeep(30)
        artifactNumToKeep(5)
    }

    properties {
        disableConcurrentBuilds()

        // All parameters in one block (dynamic DSL), in the order shown on the form.
        // Active Choices scripts are sandboxed, so they need no manual approval.
        parameters {
            parameterDefinitions {
                choice {
                    name('ACTION')
                    choices(['create', 'download', 'restore', 'delete', 'rotate'].join('\n'))
                    description('create: new backup | download: publish the selected backup as a build artifact | ' +
                                'restore: restore the selected backup into TARGET_DB | ' +
                                'delete: remove the selected backup | rotate: remove backups older than RETENTION_DAYS')
                }
                string {
                    name('BACKUP_DIR')
                    defaultValue(DEFAULTS.BACKUP_DIR)
                    description('Backup directory on the Jenkins controller (shared disk)')
                    trim(true)
                }
                activeChoiceHtml {
                    name('BACKUPS_INFO_TABLE')
                    description('Backups currently stored in BACKUP_DIR')
                    randomName('choice-parameter-backups-info-table')
                    choiceType('ET_FORMATTED_HTML')
                    omitValueField(true)
                    referencedParameters('BACKUP_DIR')
                    script {
                        groovyScript {
                            script {
                                script(BACKUPS_TABLE_SCRIPT)
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
                    description('Backup file for download / restore / delete (newest first)')
                    randomName('choice-parameter-selected-backup')
                    choiceType('PT_SINGLE_SELECT')
                    filterable(false)
                    filterLength(1)
                    referencedParameters('BACKUP_DIR')
                    script {
                        groovyScript {
                            script {
                                script(BACKUP_FILES_SCRIPT)
                                sandbox(true)
                            }
                            fallbackScript {
                                script('return []')
                                sandbox(true)
                            }
                        }
                    }
                }
                string {
                    name('MONGO_HOST')
                    defaultValue(DEFAULTS.MONGO_HOST)
                    description('MongoDB host')
                    trim(true)
                }
                string {
                    name('MONGO_PORT')
                    defaultValue(DEFAULTS.MONGO_PORT)
                    description('MongoDB port')
                    trim(true)
                }
                string {
                    name('MONGO_DB')
                    defaultValue(DEFAULTS.MONGO_DB)
                    description('Database to back up')
                    trim(true)
                }
                string {
                    name('TARGET_DB')
                    defaultValue(DEFAULTS.TARGET_DB)
                    description('Database to restore into (its collections are replaced)')
                    trim(true)
                }
                string {
                    name('MONGO_AUTH_SOURCE')
                    defaultValue(DEFAULTS.MONGO_AUTH_SOURCE)
                    description('Authentication database')
                    trim(true)
                }
                string {
                    name('MONGO_CREDENTIALS_ID')
                    defaultValue(DEFAULTS.MONGO_CREDENTIALS_ID)
                    description('Jenkins username/password credential for MongoDB')
                    trim(true)
                }
                string {
                    name('RETENTION_DAYS')
                    defaultValue(DEFAULTS.RETENTION_DAYS)
                    description('Backups older than this number of days are removed by rotation')
                    trim(true)
                }
                booleanParam {
                    name('AUTO_ROTATE_AFTER_DUMP')
                    defaultValue(DEFAULTS.AUTO_ROTATE_AFTER_DUMP)
                    description('Run rotation after a successful create')
                }
            }
        }
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(env.BACKUP_REPO_URL ?: 'https://github.com/artemklv/jenkins-backup.git')
                        // credentials('git-credentials') // for a private repository
                    }
                    branch(env.BACKUP_REPO_BRANCH ?: 'main')
                }
            }
            scriptPath('.jenkins/Jenkinsfile.backup')
            lightweight(true)
        }
    }
}
