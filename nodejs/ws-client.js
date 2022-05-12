const WebSocket = require('ws');

const options = {origin: 'localhost:9000'}
const socket = new WebSocket('ws://localhost:9000/ws', options);

socket.onopen = function() {
  console.info('[open] Connection established');
  socket.send(JSON.stringify({id: 'guest', token: 'test'}));
};

socket.onmessage = function(event) {
  const data = JSON.parse(event.data);
  console.info(`[message] Data received from server: ${JSON.stringify(data)}`);
};

socket.onclose = function(event) {
  if (event.wasClean) {
    console.info(`[close] Connection closed cleanly,
     code=${event.code} reason=${event.reason}`);
  } else {
    // e.g. server process killed or network down
    // event.code is usually 1006 in this case
    console.info('[close] Connection died');
  }
};

socket.onerror = function(error) {
  console.error(`[error] ${error.message}`);
};
