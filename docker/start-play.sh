#!/bin/bash

cd /root
. "/root/.sdkman/bin/sdkman-init.sh"
./meso-alert-1.0-SNAPSHOT/bin/meso-alert -Dconfig.file=application-production.conf
