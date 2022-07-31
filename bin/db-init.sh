#!/bin/bash

source docker/.env
export POSTGRES_PORT=$POSTGRES_PORT
export POSTGRES_HOST=$POSTGRES_HOST
make -e db-init