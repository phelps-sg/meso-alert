# meso-alert

Mesonomics crypto-currency alert service.

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
