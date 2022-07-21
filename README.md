# meso-alert

Mesonomics crypto-currency alert service, written as a
[Scala Play Framework](https://www.playframework.com/documentation/2.8.x/ScalaHome) web application.

## Development Environment

The instructions below have been tested using [Pop!_OS 22.04 LTS](https://pop.system76.com/), but should
work with other Debian-based Linux distros.  

In principle the application can be built and run using macOS, but this has not been tested and the 
installation scripts will need to be adapted.

## Installation

To install the tools needed to build and run the application from the shell run:

~~~bash
make install-dev
~~~

Ensure that docker is logged into the container registry:

~~~bash
docker login registry.gitlab.com
~~~

## Deploying to Slack

Create a [new Slack App](https://api.slack.com/apps?new_app=1) from the [slack-manifest.yml](./slack-manifest.yml) 
file.

## Configuration

Run the following in a shell from the project root directory, replacing <password> with the 
postgres password you wish to use for the local staging database:

~~~bash
cat << EOF > docker/.env
POSTGRES_PASSWORD=<password>
SLACK_CLIENT_ID=<obtain from slack app>
SLACK_CLIENT_SECRET=<obtain from slack app>
POSTGRES_PORT=5432
POSTGRES_HOST=172.17.0.1
SODIUM_KEY=`sbt "runMain util.GenerateSodiumKey" | awk '/private-key:/ {print $2}'`
PLAY_KEY=`head -c 32 /dev/urandom | base64`
EOF
~~~

## Running


### Server

The server application can be run in three different modes:

1. development mode,
2. production mode in staging environment, and
3. production mode in the production environment.


To build and run the server in development mode (1), from the project root directory run:

~~~bash
make sbt-run
~~~

To build and run the server in production mode in a staging environment (2), from the project root directory run:

~~~bash
make docker-server-start
~~~

#### A note on server configuration

For modes 1 and 2, the application can be run on a local development machine.  For mode 3,
the application is deployed into a kubernetes cluster.

In mode 1, the application configuration is stored in `conf/application.conf`.  This file
is automatically configured from `docker/.env` by the `staging-config` make target.

Both production modes (2 and 3) use
the same docker image. For mode 2, application secrets and configuration are obtained
from `docker/.env`. However, in case 3 application secrets are decrypted
from [k8/sealed-secrets.yaml](k8/sealed-secrets.yaml) using
[Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets) and then mounted underneath
`/etc/secrets` inside the docker container. On startup the docker image checks for
`/etc/secrets`, and if present it loads the corresponding secrets and settings in `docker/.env`
are ignored.

### Websocket client

To start the javascript websocket client:

~~~bash
make client-start
~~~

### Managing Slack webhooks

There is a REST API to manage
[Slack webhooks](https://slack.com/intl/en-gb/help/articles/115005265063-Incoming-webhooks-for-Slack).

#### Registering a new webhook

The following example registers a new webhook with a threshold of 20000000 Satoshi.

~~~bash
curl -X POST http://localhost:9000/api/webhooks/register --data \
'{"uri":"https://hooks.slack.com/services/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", "threshold":20000000}' \
-H 'Content-Type: application/json'
~~~

#### Starting a webhook

~~~bash
curl -X POST http://localhost:9000/api/webhooks/start --data \
'{"uri":"https://hooks.slack.com/services/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"}' \
-H 'Content-Type: application/json'
~~~

#### Stopping a webhook

~~~bash
curl -X POST http://localhost:9000/api/webhooks/stop --data \
'{"uri":"https://hooks.slack.com/services/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"}' \
-H 'Content-Type: application/json'
~~~

## Making changes to the code

Please read the [contributing guidelines](CONTRIBUTING.md) before making any changes.

