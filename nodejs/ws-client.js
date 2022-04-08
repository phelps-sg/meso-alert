const WebSocket = require('ws');

const socket = new WebSocket('ws://localhost:9000/ws');

socket.onopen = function(e) {
  console.info('[open] Connection established');
  console.info('Sending to server');
  socket.send('My name is John');
};

socket.onmessage = function(event) {
  console.info(`[message] Data received from server: ${event.data}`);
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
