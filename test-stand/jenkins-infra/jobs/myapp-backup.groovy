// Job DSL definition of the 'myapp-backup' job (loaded by jenkins-casc.yaml).
//
// All job parameters live here, so the job is fully configured on every Jenkins
// start and the parameter form works before the first build.
// The pipeline itself is .jenkins/Jenkinsfile.backup from BACKUP_REPO_URL.

def env = System.getenv()

// Job settings: hidden parameters, shown and edited only on the job Configure page
// (edits there are overwritten by this file on the next Jenkins start).
// Each entry: name -> [default value, description]
def SETTINGS = [
    BACKUP_DIR            : ['/var/backups/mongodb', 'Backup directory on the Jenkins controller (shared disk)'],
    MONGO_HOST            : ['mongodb-test', 'MongoDB host'],
    MONGO_PORT            : ['27017', 'MongoDB port'],
    MONGO_DB              : ['myapp', 'Database to back up'],
    TARGET_DB             : ['myapp_restore', 'Database to restore into (its collections are replaced)'],
    MONGO_AUTH_SOURCE     : ['admin', 'Authentication database'],
    MONGO_CREDENTIALS_ID  : ['mongo-backup-credentials', 'Jenkins username/password credential for MongoDB'],
    RETENTION_DAYS        : ['7', 'Backups older than this number of days are removed by rotation'],
    AUTO_ROTATE_AFTER_DUMP: ['true', 'Run rotation after a successful create (true/false)'],
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
// Download links: the default backup directory is also mounted into
// JENKINS_HOME/userContent/backups, which Jenkins serves at /userContent/backups/
def linkable = dir.path == '/var/backups/mongodb'
def downloadLink = { String name ->
    if (!linkable || !(name ==~ /[A-Za-z0-9._-]+/)) {
        return '-'
    }
    return '<a href="/userContent/backups/' + name + '">download</a>'
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
    def checksum = checksumOf(f.name)
    def values = [i + 1, esc(f.name), sizeMb, fmt.format(new Date(f.lastModified())), checksum, downloadLink(f.name)]
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

        // Build form: ACTION, the backup table and the file list; everything else is a hidden setting.
        // All parameters are in one block (dynamic DSL).
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
                // Job settings: not shown on the build form
                SETTINGS.each { settingName, setting ->
                    wHideParameterDefinition {
                        name(settingName)
                        defaultValue(setting[0])
                        description(setting[1])
                    }
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
