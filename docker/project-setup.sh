#!/bin/bash

cat > /root/meso-alert-config.sh << EOF
export POSTGRES_PASSWORD=$1
export POSTGRES_PORT=$2
export SODIUM_KEY=$3
export PLAY_KEY=$4
export SLACK_CLIENT_ID=$5
export SLACK_CLIENT_SECRET=$6
export POSTGRES_HOST=$7
export EMAIL_SMTP_HOST=$8
export EMAIL_SMTP_PORT=$9
export EMAIL_HOST=${10}
export EMAIL_HOST_PASSWORD=${11}
export EMAIL_DESTINATION=${12}
export SLACK_DEPLOY_URL=${13}

EOF
