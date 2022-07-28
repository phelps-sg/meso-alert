#!/bin/bash

cat > meso-alert-ci-config.sh << EOF
export SLACK_TEST_EMAIL='$1'
export SLACK_TEST_PASSWORD='$2'
export SLACK_TEST_WORKSPACE='$3'

EOF
