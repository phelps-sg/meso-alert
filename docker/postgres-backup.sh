#!/bin/bash

send_slack_notification() {
  payload='payload={"text": "'$1'"}'
  cmd1= curl --data "$payload" "${APP_SLACK_WEBHOOK}" || true
}

cd /root || exit

date1=$(date +%Y%m%d-%H%M)

mkdir pg-backup

echo "$PG_HOST:$PG_PORT:$PG_DATABASE:$PG_USER:$PG_PASS" > /root/.pgpass
chmod 0600 /root/.pgpass

file_name="pg-backup/pg-backup-$date1.tar.gz"

notification_msg="$BACKUP_APP: postgres backup of $file_name to $S3_BUCKET FAILED"

if pg_dump --no-password -h $PG_HOST -p $PG_PORT -U $PG_USER --format=tar $PG_DATABASE | gzip -c >"$file_name"; then
  if aws s3 cp "$file_name" "$S3_BUCKET"; then
    echo "$BACKUP_APP: postgres backup of $file_name to $S3_BUCKET SUCCESS"
  else
    send_slack_notification "$notification_msg"
  fi
fi
