#!/bin/bash

. "/root/.sdkman/bin/sdkman-init.sh"

cd /root

if [ -d "/etc/secrets" ]; then
  echo "Running in k8 environment"

  PLAY_KEY=$(cat /etc/secrets/play/secret | base64)

  POSTGRES_PASSWORD=$(cat /etc/secrets/postgres/password)

  SLACK_BOT_TOKEN=""
  SLACK_CLIENT_SECRET=$(cat /etc/secrets/slack/client_secret)
  SLACK_CLIENT_ID=$(cat /etc/secrets/slack/client_id)
  SLACK_DEPLOY_URL='https://slack.com/oauth/v2/authorize?client_id=581743246742.3822254705591&scope=commands,chat:write,links:write&user_scope='

  SODIUM_KEY=$(cat /etc/secrets/sodium/key)

  POSTGRES_PORT=5432
  POSTGRES_HOST="meso-alert-postgres"

  EMAIL_SMTP_HOST=smtp-relay.gmail.com
  EMAIL_SMTP_PORT=587
  EMAIL_HOST=$(cat /etc/secrets/email/username)
  EMAIL_HOST_PASSWORD=$(cat /etc/secrets/email/password)
  EMAIL_DESTINATION=steve@symbiotica.ai

else

  echo "Running in staging environment"
  . "/root/meso-alert-config.sh"

fi

#
# Non-secret k8 configuration changes should be made directly below without
# using an environment variable.
#
cat <<EOF > application-production.conf

email.smtpHost = "${EMAIL_SMTP_HOST}"
email.smtpPort = "${EMAIL_SMTP_PORT}"
email.host = "${EMAIL_HOST}"
email.hostPassword = "${EMAIL_HOST_PASSWORD}"
email.destination = "${EMAIL_DESTINATION}"

play.filters.disabled+=play.filters.hosts.AllowedHostsFilter
play.http.secret.key="${PLAY_KEY}"
play.i18n.langs = ["en"]

slack.clientId = "${SLACK_CLIENT_ID}"
slack.clientSecret = "${SLACK_CLIENT_SECRET}"
slack.botToken = "${SLACK_BOT_TOKEN}"
slack.deployURL = "${SLACK_DEPLOY_URL}"

sodium.secret="${SODIUM_KEY}"

meso-alert.db = {
  connectionPool = "HikariCP" //use HikariCP for our connection pool
  dataSourceClass = "org.postgresql.ds.PGSimpleDataSource"
  properties = {
    serverName = "${POSTGRES_HOST}"
    portNumber = "${POSTGRES_PORT}"
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

email.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 2
  }
}

EOF

unzip ./target/universal/meso-alert-1.0-SNAPSHOT.zip

./meso-alert-1.0-SNAPSHOT/bin/meso-alert -Dconfig.file=application-production.conf
