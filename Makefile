
SHELL=/bin/bash
SDK_INIT=source ~/.sdkman/bin/sdkman-init.sh

EXPORT_ENV=export PLAY_SECRET=$(PLAY_SECRET)

apt-update:
	sudo apt update

curl-install: apt-update
	sudo apt-get install -y curl

docker-install: apt-update
	sudo apt-get install -y docker

sdkman-install:
	curl -s "https://get.sdkman.io" | bash

sbt-install:
	$(SDK_INIT); sdk install sbt

nvm-install:
	curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.1/install.sh | bash

nodejs-install: nvm-install
	nvm install --lts

install-dev: curl-install sdkman-install sbt-install docker-install nodejs-install

sbt-build:
	$(SDK_INIT); sbt dist

docker-build: sbt-build
	$(EXPORT_ENV); cd docker; sudo -E docker-compose up --no-start --build

docker-push: docker-build
	sudo docker push registry.gitlab.com/mesonomics/meso-alert/play-server

docker-server-start: docker-build
	$(EXPORT_ENV); cd docker; sudo -E docker-compose up

client-install:
	cd nodejs; npm install

client-start: client-install
	cd nodejs; nodejs ws-client.js
