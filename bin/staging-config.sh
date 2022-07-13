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

EOF
