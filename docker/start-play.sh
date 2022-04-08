#!/bin/bash

cd /root
. ./settings.sh
. "/root/.sdkman/bin/sdkman-init.sh"
./meso-alert-1.0-SNAPSHOT/bin/meso-alert -Dplay.http.secret.key=\'${PLAY_KEY}\'
