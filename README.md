# meso-alert

Mesonomics crypto-currency alert service.

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

## Configuration

Run the following in a shell from the project root directory, replacing <password> with the 
postgres password you wish to use for the local staging database:

~~~bash
cat << EOF > docker/.env
POSTGRES_PASSWORD=<password>
SLACK_BOT_TOKEN=<obtain from slack app>
POSTGRES_PORT=5436
SODIUM_KEY=`sbt "runMain util.GenerateSodiumKey" | awk '/private-key:/ {print $2}'`
EOF
~~~

## Running

### Server

To build and run the server in development mode, from the project root directory run:

~~~bash
make sbt-run
~~~

To build and run the server in production mode, from the project root directory run:

~~~bash
export POSTGRES_PASSWORD=<changeme>
export PLAY_SECRET=<changeme>
make -e docker-server-start
~~~

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
