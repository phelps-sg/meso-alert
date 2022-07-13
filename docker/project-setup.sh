#!/bin/bash

cat > /root/meso-alert-config.sh << EOF
export POSTGRES_PASSWORD=$1
export POSTGRES_PORT=$2
export SODIUM_KEY=$3
export PLAY_KEY=$4
export SLACK_CLIENT_ID=$5
export SLACK_CLIENT_SECRET=$6
export POSTGRES_HOST=$7
EOF
