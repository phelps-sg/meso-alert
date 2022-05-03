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
export PLAY_SECRET=<changeme>
make -e docker-server-start
~~~

### Websocket client

~~~bash
make client-start
~~~
