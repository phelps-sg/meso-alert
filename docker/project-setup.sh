#!/bin/bash

cd /root

PLAY_KEY=$1
POSTGRES_PASSWORD=$2

cat <<EOF > application-production.conf
play.http.secret.key='${PLAY_KEY}'
play.filters.disabled+=play.filters.hosts.AllowedHostsFilter

meso-alert.db = {
  connectionPool = "HikariCP" //use HikariCP for our connection pool
  dataSourceClass = "org.postgresql.ds.PGSimpleDataSource"
  properties = {
    serverName = "172.17.0.1"
    portNumber = "5436"
    databaseName = "meso-alert"
    user = "meso-alert"
    password = "${POSTGRES_PASSWORD}"
  }
  numThreads = 12
  queueSize = 50000
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
