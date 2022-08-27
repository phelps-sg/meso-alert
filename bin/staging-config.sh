#!/bin/bash

. docker/.env

cat <<EOF > ./conf/application.conf

play.filters.hosts {
  allowed = [".ngrok.io", "localhost"]
}
play.i18n.langs = ["en"]

sodium.secret = "${SODIUM_KEY}"

slack.clientId = "${SLACK_CLIENT_ID}"
slack.clientSecret = "${SLACK_CLIENT_SECRET}"
slack.deployURL = "${SLACK_DEPLOY_URL}"

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
  numThreads = 6
  queueSize = 50000
}

database.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 2
  }
}

slackChat.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 2
  }
}

email.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 2
  }
}

email.smtpHost = "${EMAIL_SMTP_HOST}"
email.smtpPort = ${EMAIL_SMTP_PORT}
email.host = "${EMAIL_HOST}"
email.hostPassword = "${EMAIL_HOST_PASSWORD}"
email.destination = "${EMAIL_DESTINATION}"

EOF
