# Chat Adapters

It is a chat adapter's job to listen for messages, run them through parsing and
evaluation, and route the response back to wherever it came from.

Some chat adapters may support multiple "channels" (e.g. irc), and therefore
must keep track of which channel a command originated from and send the response
back to that channel.
