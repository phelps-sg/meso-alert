# meso-alert

Mesonomics crypto-currency alert service.

## Installation

To install the tools needed to build and run the application from the shell run:

~~~bash
make install-dev
~~~

Ensure that docker is logged into the container registry:

~~~bash
docker login registry.gitlab.com
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
