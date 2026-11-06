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

def DOWNLOAD_LOG_SCRIPT = '''
// Rendered only when the SHOW_DOWNLOAD_LOG checkbox is checked.
// The checkbox value is "on" when checked and empty otherwise; it is not bound
// when the form is first rendered on the server.
def show = ''
try {
    show = SHOW_DOWNLOAD_LOG
} catch (MissingPropertyException ignored) {
}
if (!(show in ['on', 'true'])) {
    return ''
}
// Audit Trail log files (see jenkins-casc.yaml); the newest entries go first
def auditDir = new File('/var/jenkins_home/audit')
def logFiles = (auditDir.isDirectory() ? auditDir.listFiles() : [])
    .findAll { it.isFile() && it.name.startsWith('audit.log') && !it.name.endsWith('.lck') }
    .sort { a, b -> a.lastModified() <=> b.lastModified() }
def esc = { String s -> s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }
def rows = []
logFiles.each { f ->
    f.text.split('\\n').each { line ->
        // Format: "<date> /userContent/backups/<file> by <user> from <ip>"
        def m = line =~ /^(.+?) \\/userContent\\/backups\\/(\\S+) by (\\S+) from (\\S+)/
        if (m.find()) {
            rows.add([m.group(1), m.group(2), m.group(3), m.group(4)])
        }
    }
}
if (rows.isEmpty()) {
    return '<p>No downloads recorded yet.</p>'
}
rows = rows.reverse()
// The HTML sanitizer drops CSS overflow, so a read-only textarea provides the
// scroll bar: at most 8 lines are visible and the form does not grow
def line = { r -> String.format('%-28s %-12s %-16s %s', r[0], r[2], r[3], r[1]) }
def text = [line(['Time (UTC)', 'File', 'User', 'IP'])] + rows.collect { line(it) }
def visible = Math.min(text.size(), 8)
return '<textarea readonly rows="' + visible + '" cols="110" style="font-family:monospace">' +
       esc(text.join('\\n')) + '</textarea>' +
       "<p>Total: ${rows.size()} download(s)</p>"
'''

pipelineJob('myapp-backup') {
    description('MongoDB backups: create, restore, delete, rotate; download via the backup table (.jenkins/Jenkinsfile.backup)')

    logRotator {
        numToKeep(30)
    }

    properties {
        disableConcurrentBuilds()

        // Build form: ACTION, the backup table, the file list and the download log;
        // everything else is a hidden setting.
        // All parameters are in one block (dynamic DSL).
        // Active Choices scripts are sandboxed, so they need no manual approval.
        parameters {
            parameterDefinitions {
                choice {
                    name('ACTION')
                    choices(['create', 'restore', 'delete', 'rotate'].join('\n'))
                    description('create: new backup | ' +
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
                    description('Backup file for restore / delete (newest first)')
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
                booleanParam {
                    name('SHOW_DOWNLOAD_LOG')
                    defaultValue(false)
                    description('Show who downloaded backups and when')
                }
                activeChoiceHtml {
                    name('DOWNLOAD_LOG')
                    description('')
                    randomName('choice-parameter-download-log')
                    choiceType('ET_FORMATTED_HTML')
                    omitValueField(true)
                    referencedParameters('SHOW_DOWNLOAD_LOG')
                    script {
                        groovyScript {
                            script {
                                script(DOWNLOAD_LOG_SCRIPT)
                                sandbox(true)
                            }
                            fallbackScript {
                                script('return "<p>Failed to read the download log</p>"')
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
