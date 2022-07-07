
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

libsodium-install:
	sudo apt-get install -y libsodium-dev

sdkman-install:
	curl -s "https://get.sdkman.io" | bash

jdk-install:
	$(SDK_INIT); sdk install java $(JAVA_VERSION); sdk default java 11.0.12-open

sbt-install:
	$(SDK_INIT); sdk install sbt

nvm-install:
	curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.1/install.sh | bash

install-dev: curl-install sdkman-install jdk-install sbt-install docker-install nvm-install libsodium-install

staging-config:
	bin/staging-config.sh

sbt-run: staging-config docker-db-start
	$(SDK_INIT); sbt run

sbt-test:
	$(SDK_INIT); sbt test

sbt-build:
	$(SDK_INIT); sbt dist

sbt-scalafix-check:
	$(SDK_INIT); sbt "scalafixAll --check"

sbt-scalafix:
	$(SDK_INIT); sbt "scalafixAll"

docker-push: docker-build
	sudo docker push registry.gitlab.com/mesonomics/meso-alert/play-server; \
	sudo docker push registry.gitlab.com/mesonomics/meso-alert/ci

docker-server-start: docker-build
	$(EXPORT_ENV); cd docker; sudo -E docker-compose up

dir-postgres-create:
	sudo mkdir -p /data/1/meso-alert-db

docker-db-start: dir-postgres-create
	$(EXPORT_ENV); cd docker; sudo -E docker-compose up -d postgres-db

nodejs-install:
	$(NVM_INIT); nvm install 16.15.0

client-install: nodejs-install
	$(NVM_INIT); cd nodejs; npm install

client-start: client-install
	$(NVM_INIT); cd nodejs; node ws-client.js
