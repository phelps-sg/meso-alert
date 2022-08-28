# meso-alert

Mesonomics cryptocurrency alert service, written as a
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

### Ensure that docker is logged into the container registry

#### On local development machines

~~~bash
docker login registry.gitlab.com
~~~

#### On Kubernetes clusters 

~~~bash
kubectl create secret docker-registry regcred --docker-server=registry.gitlab.com \
        --docker-username=<Gitlab username> --docker-password=<Gitlab access-token> \
        --docker-email=<Gitlab email>
~~~

## Configuration

Run the following in a shell from the project root directory, replacing <password> with the 
postgres password you wish to use for the local staging database:

~~~bash
cat << EOF > docker/.env
POSTGRES_PASSWORD='<password>'
SLACK_CLIENT_ID='<copy from Slack / Basic Information / App Credentials / Client ID>'
SLACK_CLIENT_SECRET='<copy from Slack / Basic Information / App Credentials / Client Secret>'
POSTGRES_PORT=5432
POSTGRES_HOST='172.17.0.1'
SODIUM_KEY=`sbt "runMain util.GenerateSodiumKey" | awk '/private-key:/ {print $2}'`
PLAY_KEY=`head -c 32 /dev/urandom | base64`
EMAIL_SMTP_HOST='smtp-relay.gmail.com'
EMAIL_SMTP_PORT=587
EMAIL_HOST='<source address>'
EMAIL_HOST_PASSWORD='<changeMe>'
EMAIL_DESTINATION='feedback@symbiotica.ai'
SLACK_DEPLOY_URL='<copy from Slack / Manage Distribution / Sharable URL>'
AUTH0_DOMAIN='<copy from auth0 domain>'
AUTH0_CLIENT_ID='<copy from auth0 client id>'
AUTH0_AUDIENCE='<copy from auth0 API>'
EOF
~~~

## Running

### Server

The server application can be run in three different modes:

1. development mode,
2. production mode in local development environment, and
3. production mode in the actual production environment.

To build and run the server in development mode (1), from the project root directory run:

~~~bash
bin/db-init.sh
make sbt-run
~~~

### Building and running the app from scratch in production mode in a local environment.

1. Initialise the database with a new schema:
~~~bash
bin/db-init.sh
~~~
2. Start a new ngrok tunnel on port 9000 by running the following in a byobu session:
~~~bash
ngrok http 9000
~~~
3. Make a note of the public ngrok address that is used to forward requests. This is shown in the _Forwarding_ field.
4. Login to slack in a browser and head over to https://api.slack.com/apps.
5. Click **Create New App** -> **From an app manifest** and select a test workspace to deploy the app.
6. Copy the contents from [slack-manifest-staging.yml](./slack-manifest-staging.yml) into the YAML input, **making sure to change the urls in the manifest to your own ngrok forwarding url from step 3**.
7. After creating the app, copy the _Client ID_ and _Client Secret_ into your `docker/.env` file.
9. From your app's home page, head over to **Basic Information** -> **Manage Distribution** -> **Distribute App**, and copy the _Sharable URL_ into the `SLACK_DEPLOY_URL` in `docker/.env`
9. After filling in the rest of the configuration fields in `docker/.env`, run the app with the command:
~~~bash
make docker-server-start
~~~
10. With the app running, head over to http://localhost:9000, and add the app to a test workspace.
11. In the test workspace where you deployed the app, issue the command `@block-insights` to a channel where you want to receive alerts.

#### A note on server configuration

For modes 1 and 2, the application can be run on a local development machine.  For mode 3,
the application is deployed into a kubernetes cluster.

In mode 1, the application configuration is stored in `conf/application.conf`.  This file
is automatically configured from `docker/.env` by the `staging-config` make target.

Both production modes (2 and 3) use the same docker image. For mode 2, application secrets
and configuration are obtained from `docker/.env`. However, in mode 3 application secrets are decrypted
from [k8/staging/sealed-secrets.yaml](k8/staging/sealed-secrets.yaml) using
[Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets) and then mounted underneath
`/etc/secrets` inside the docker container. On startup the docker image checks for
`/etc/secrets`. If this directory is present it loads the corresponding secrets, and ignores the 
settings in `docker/.env`.

Non-secret configuration changes for kubernetes production mode (3) should be made directly
to [docker/start-play.sh](docker/start-play.sh).

For changes that affect the unit-testing environment, make configuration changes to
[test/resources/application.test.conf](test/resources/application.test.conf).

##### Summary: adding new configuration variables

If new application configuration variables are added, then all the following files need to be updated:

- docker/.env
- [bin/staging-config.sh](bin/staging-config.sh)
- [docker/docker-compose.yml](docker/docker-compose.yml)
- [docker/meso-alert.docker](docker/meso-alert.docker)
- [docker/start-play.sh](docker/start-play.sh)
- [test/resources/application.test.conf](test/resources/application.test.conf)
- This file

If the new configuration involves secret values such as passwords, then the following files also need to be updated:

- [k8/web-application.yaml](k8/web-application.yaml)
- [k8/staging/sealed-secrets.yaml](k8/staging/sealed-secrets.yaml)
- [k8/production/sealed-secrets.yaml](k8/staging/sealed-secrets.yaml)

For the latter, use a command similar to the following from within the production k8 cluster:

~~~bash
cat secret.yaml | kubeseal --controller-namespace default --controller-name sealed-secrets --format yaml > sealed-secret.yaml
~~~

and then edit [k8/staging/sealed-secrets.yaml](k8/staging/sealed-secrets.yaml) with the contents of `sealed-secret.yaml`, taking 
care to delete the temporary file once the application is successfully deployed.

### Websocket client

Currently, this functionality is disabled by default.  To enable it un-comment the relevant line in
[/conf/routes](/conf/routes).

To start the javascript websocket client:

~~~bash
make client-start
~~~

### Managing Slack webhooks

There is a REST API to manage
[Slack webhooks](https://slack.com/intl/en-gb/help/articles/115005265063-Incoming-webhooks-for-Slack).

Currently, this functionality is disabled by default.  To enable it un-comment the relevant lines in 
[/conf/routes](/conf/routes).

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

## Connecting to the production database

Use a command similar to the following:

~~~bash
kubectl port-forward <postgres-pod-name> 5454:5432
~~~

The above will forward connections to port 5454 on localhost to the production postgres instance.  To
obtain pod names, run the command:

~~~bash
kubectl get pods
~~~

## Making changes to the code

Please read the [contributing guidelines](CONTRIBUTING.md) before making any changes.

