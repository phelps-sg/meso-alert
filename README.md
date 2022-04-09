# meso-alert

Mesonomics crypto-currency alert service.

## Installation

To install the tools needed to build and run the application from the shell run:

~~~bash
make install-dev
~~~

## Running

### Server

To build and run the server from the project root directory:

~~~bash
export PLAY_SECRET=<changeme>
make -e docker-server-start
~~~

### Client

~~~bash
make client-start
~~~
