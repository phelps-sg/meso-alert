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

## Features

- Users can monitor a BTC address or wallet and receive real-time alerts via Slack.  
    - Alerts will provide information on any transactions associated with the specified addresses or wallets.
    - Alerts will contain historical exchange rates for all commonly-traded currency pairs (BTC/USD, BTC/GBP, etc.).
- Users can register for a free or paid account on the web site.
- Payments can be taken by BTC, credit-card or pay-pal.
- Users can request alerts via an end-user web front-end, or via an API.
- API access is throttled according to account tier.
