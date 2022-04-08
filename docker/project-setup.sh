#!/bin/bash

cd /root
PLAY_KEY=$1
cat <<EOF > settings.sh
export PLAY_KEY=$PLAY_KEY
EOF
unzip ./target/universal/meso-alert-1.0-SNAPSHOT.zip
