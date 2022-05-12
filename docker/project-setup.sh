#!/bin/bash

cd /root
PLAY_KEY=$1
cat <<EOF > application-production.conf
play.http.secret.key='${PLAY_KEY}'
play.filters.disabled+=play.filters.hosts.AllowedHostsFilter
EOF
unzip ./target/universal/meso-alert-1.0-SNAPSHOT.zip
