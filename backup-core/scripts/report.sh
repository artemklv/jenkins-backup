#!/usr/bin/env bash
# Generate an HTML report with the backups of one environment (download links
# included) and the download log recorded by the Audit Trail plugin.
#
# Usage: report.sh <output directory>
#
# Environment:
#   ENV_NAME       Environment name shown in the title (default: unknown)
#   BACKUP_DIR     Directory with backups (default: /var/backups/mongodb)
#   DOWNLOAD_URL   URL path under which Jenkins serves BACKUP_DIR,
#                  e.g. /userContent/backups/test (no links when empty)
#   AUDIT_LOG_DIR  Audit Trail log directory (default: /var/jenkins_home/audit)
#   LOG_LIMIT      Maximum number of download log entries (default: 200)
set -euo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [report] $*"; }

[[ $# -eq 1 ]] || { echo "Usage: $0 <output directory>" >&2; exit 1; }

out="$1"
ENV_NAME="${ENV_NAME:-unknown}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/mongodb}"
DOWNLOAD_URL="${DOWNLOAD_URL:-}"
AUDIT_LOG_DIR="${AUDIT_LOG_DIR:-/var/jenkins_home/audit}"
LOG_LIMIT="${LOG_LIMIT:-200}"

esc() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }

mkdir -p "$out"

# The report is served with a strict Content Security Policy:
# styles must come from a file, inline styles are blocked
cat > "${out}/style.css" <<'CSS'
body { font-family: sans-serif; margin: 16px; }
table { border-collapse: collapse; margin-bottom: 8px; }
th, td { border: 1px solid #999; padding: 4px 8px; text-align: left; }
td.num { text-align: right; }
code { font-size: 12px; }
.log { max-height: 320px; overflow-y: auto; display: inline-block; }
.muted { color: #666; }
CSS

html="${out}/index.html"
{
  echo '<!DOCTYPE html><html><head><meta charset="utf-8">'
  echo "<title>Backups: $(echo "$ENV_NAME" | esc)</title>"
  echo '<link rel="stylesheet" href="style.css"></head><body>'
  echo "<h2>Backups: $(echo "$ENV_NAME" | esc)</h2>"
  echo "<p class=\"muted\">Directory: <code>$(echo "$BACKUP_DIR" | esc)</code>. Generated: $(date -u '+%Y-%m-%d %H:%M:%S') UTC</p>"
} > "$html"

# --- Backups ---------------------------------------------------------------
count=0
rows=""
if [[ -d "$BACKUP_DIR" ]]; then
  while IFS=$'\t' read -r mtime size name; do
    [[ -n "$name" ]] || continue
    count=$((count + 1))
    size_mb="$(awk -v s="$size" 'BEGIN { printf "%.2f", s / 1048576 }')"
    modified="$(date -u -d "@${mtime%.*}" '+%Y-%m-%d %H:%M:%S')"
    sum_file="${BACKUP_DIR}/${name}.sha256"
    if [[ -f "$sum_file" ]]; then
      hash="$(cut -d' ' -f1 "$sum_file")"
      [[ "$hash" =~ ^[0-9a-f]{64}$ ]] && checksum="<code>${hash}</code>" || checksum="invalid"
    else
      checksum="missing"
    fi
    link="-"
    if [[ -n "$DOWNLOAD_URL" && "$name" =~ ^[A-Za-z0-9._-]+$ ]]; then
      link="<a href=\"${DOWNLOAD_URL}/${name}\" download>download</a>"
    fi
    rows+="<tr><td>${count}</td><td>$(echo "$name" | esc)</td><td class=\"num\">${size_mb}</td>"
    rows+="<td>${modified}</td><td>${checksum}</td><td>${link}</td></tr>"$'\n'
  done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.archive.gz' -printf '%T@\t%s\t%f\n' | sort -rn)
fi

{
  echo '<h3>Backups</h3>'
  if [[ $count -eq 0 ]]; then
    echo '<p>No backups found.</p>'
  else
    echo '<table><tr><th>#</th><th>File</th><th>Size, MB</th><th>Modified (UTC)</th><th>SHA256</th><th>Download</th></tr>'
    printf '%s' "$rows"
    echo "</table><p>Total: ${count} backup(s)</p>"
  fi
} >> "$html"

# --- Download log ----------------------------------------------------------
# Audit Trail line format: "<date> <url> by <user> from <ip>"
entries=""
if [[ -n "$DOWNLOAD_URL" && -d "$AUDIT_LOG_DIR" ]]; then
  mapfile -t log_files < <(find "$AUDIT_LOG_DIR" -maxdepth 1 -type f -name 'audit.log*' ! -name '*.lck' -printf '%T@\t%p\n' | sort -n | cut -f2-)
  if [[ ${#log_files[@]} -gt 0 ]]; then
    entries="$(cat "${log_files[@]}" | awk -v base="${DOWNLOAD_URL}/" '
      {
        i = index($0, " " base)
        if (i == 0) next
        split(substr($0, i + 1 + length(base)), a, " ")
        if (a[2] != "by" || a[4] != "from") next
        printf "%s\t%s\t%s\t%s\n", substr($0, 1, i - 1), a[1], a[3], a[5]
      }' | tac | head -n "$LOG_LIMIT")"
  fi
fi

{
  echo '<h3>Download log</h3>'
  if [[ -z "$entries" ]]; then
    echo '<p>No downloads recorded yet.</p>'
  else
    echo '<div class="log"><table><tr><th>Time (UTC)</th><th>User</th><th>IP</th><th>File</th></tr>'
    while IFS=$'\t' read -r time file user ip; do
      echo "<tr><td>$(echo "$time" | esc)</td><td>$(echo "$user" | esc)</td><td>$(echo "$ip" | esc)</td><td>$(echo "$file" | esc)</td></tr>"
    done <<< "$entries"
    echo "</table></div><p class=\"muted\">Newest first, up to ${LOG_LIMIT} entries.</p>"
  fi
  echo '</body></html>'
} >> "$html"

log "Report for '${ENV_NAME}' written to ${html}: ${count} backup(s)"
