
SHELL=/bin/bash
SDK_INIT=source ~/.sdkman/bin/sdkman-init.sh
NVM_INIT=source ~/.nvm/nvm.sh

JAVA_VERSION=11.0.12-open

EXPORT_ENV=export PLAY_SECRET=$(PLAY_SECRET)

docker-build: sbt-build
	$(EXPORT_ENV); cd docker; sudo -E docker-compose up --no-start --build

apt-update:
	sudo apt update

curl-install: apt-update
	sudo apt-get install -y curl

docker-install: apt-update
	sudo apt-get install -y docker

psql-install: apt-update
	sudo apt-get install -y postgresql-client

doctl-install:
	sudo snap install doctl; sudo snap connect doctl:kube-config

kubeseal-install:
	sudo snap install sealed-secrets-kubeseal-nsg

kubectl-install:
	sudo snap install kubectl --classic

k8-install: doctl-install kubeseal-install kubectl-install

libsodium-install:
	sudo apt-get install -y libsodium-dev

geckodriver-install:
	cd /tmp; \
	curl -Ls --output - https://github.com/mozilla/geckodriver/releases/download/v0.31.0/geckodriver-v0.31.0-linux64.tar.gz | tar zxv; \
	sudo cp geckodriver /usr/local/bin; \
	rm geckodriver

sdkman-install:
	curl -s "https://get.sdkman.io" | bash

jdk-install:
	$(SDK_INIT); sdk install java $(JAVA_VERSION); sdk default java $(JAVA_VERSION)

sbt-install:
	$(SDK_INIT); sdk install sbt

nvm-install:
	curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.1/install.sh | bash

ngrok-install:
	curl -s https://ngrok-agent.s3.amazonaws.com/ngrok.asc | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null && echo "deb https://ngrok-agent.s3.amazonaws.com buster main" | sudo tee /etc/apt/sources.list.d/ngrok.list; sudo apt update; sudo apt install ngrok

install-dev: curl-install sdkman-install jdk-install sbt-install docker-install nvm-install libsodium-install geckodriver-install ngrok-install k8-install psql-install

staging-config:
	bin/staging-config.sh

sbt-run: staging-config docker-db-start
	$(SDK_INIT); sbt run

sbt-test:
	$(SDK_INIT); sbt test

sbt-unit-tests:
	$(SDK_INIT); sbt "testOnly unittests.*"

sbt-functional-tests:
	$(SDK_INIT); sbt "testOnly functionaltests.*"

sbt-build:
	$(SDK_INIT); sbt dist

sbt-scalafix-check:
	$(SDK_INIT); sbt "scalafixAll --check"

sbt-scalafix:
	$(SDK_INIT); sbt "scalafixAll"

sbt-scalafmt-check-all:
	$(SDK_INIT); sbt "scalafmtCheckAll"

docker-push: docker-build docker-play-server-push docker-ci-push

docker-play-server-push:
	sudo docker push registry.gitlab.com/mesonomics/meso-alert/play-server

docker-ci-push:
	sudo docker push registry.gitlab.com/mesonomics/meso-alert/ci

docker-server-start: docker-build
	$(EXPORT_ENV); cd docker; sudo -E docker-compose up

dir-postgres-create:
	sudo mkdir -p /data/1/meso-alert-db

docker-db-start: dir-postgres-create
	$(EXPORT_ENV); cd docker; sudo -E docker-compose up -d postgres-db

db-init: docker-db-start
	psql --user meso-alert --db meso-alert --port ${POSTGRES_PORT} --host ${POSTGRES_HOST} -f sql/schema.sql

nodejs-install:
	$(NVM_INIT); nvm install 16.15.0

client-install: nodejs-install
	$(NVM_INIT); cd nodejs; npm install

client-start: client-install
	$(NVM_INIT); cd nodejs; node ws-client.js
