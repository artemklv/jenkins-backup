// Content Security Policy for files served by Jenkins (HTML Publisher reports,
// artifacts, workspaces). Same as the Jenkins default, plus 'allow-downloads':
// the "Backups" report links to backup files, and browsers block downloads
// from a sandboxed page without this flag. Scripts stay forbidden.
System.setProperty(
    'hudson.model.DirectoryBrowserSupport.CSP',
    "sandbox allow-same-origin allow-downloads; default-src 'none'; img-src 'self'; style-src 'self';"
)
