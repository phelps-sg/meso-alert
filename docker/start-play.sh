#!/bin/bash

. "/root/.sdkman/bin/sdkman-init.sh"

cd /root

ls -lR /etc/secrets

PLAY_KEY=$(cat /etc/secrets/play/secret | base64)
POSTGRES_PASSWORD=$(cat /etc/secrets/postgres/password)
SLACK_BOT_TOKEN=$(cat /etc/secrets/slack/bot_token)
SLACK_CLIENT_SECRET=$(cat /etc/secrets/slack/client_secret)
SLACK_CLIENT_ID=$(cat /etc/secrets/slack/client_id)

cat <<EOF > application-production.conf
play.http.secret.key="${PLAY_KEY}"
slack.clientId = "${SLACK_CLIENT_ID}"
slack.clientSecret = "${SLACK_CLIENT_SECRET}"
slack.botToken = "${SLACK_BOT_TOKEN}"
play.filters.disabled+=play.filters.hosts.AllowedHostsFilter

meso-alert.db = {
  connectionPool = "HikariCP" //use HikariCP for our connection pool
  dataSourceClass = "org.postgresql.ds.PGSimpleDataSource"
  properties = {
    serverName = "meso-alert-postgres"
    portNumber = "5432"
    databaseName = "meso-alert"
    user = "meso-alert"
    password = "${POSTGRES_PASSWORD}"
  }
  numThreads = 12
  queueSize = 50000
}

slackChat.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 12
  }
}

database.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 12
  }
}
EOF

unzip ./target/universal/meso-alert-1.0-SNAPSHOT.zip

./meso-alert-1.0-SNAPSHOT/bin/meso-alert -Dconfig.file=application-production.conf
