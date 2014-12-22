Scamper
=======

A toolkit to make it easy to write Java servers and clients that use 
[SCTP](http://en.wikipedia.org/wiki/Stream_Control_Transmission_Protocol) as their
transport, using [Netty](http://netty.io) under the hood.

Servers and clients can register `MessageType`s and `MessageHandler`s that
receive messages of those types.  Each messaage sent or received by this
library has a 3-byte header identifying the message.  The remaining payload
of a message is up to you.

For convenience, by default message payloads
are encoded using [BSON](http://en.wikipedia.org/wiki/BSON) using 
[BSON4Jackson](https://github.com/michel-kraemer/bson4jackson).  If you want
to write your own payloads, simply make the payload a
[ByteBuf](http://netty.io/5.0/api/io/netty/buffer/ByteBuf.html) and
no encoding or decoding will be done.


What It Does
------------

Allows you to create clients and servers that can pass messages very simply.
At this point the benefits of SCTP are small (multi-homing is TBD), but one
aspect can be seen in that, if you run the date-demo project, you can stop
and restart the server while the client is running, without the client
either failing or needing to do anything to reconnect.

SCTP is message-oriented, like UDP, as opposed to stream-oriented like TCP,
and has the benefit that messages do not block each other, and multiple messages
can be on the wire on the same connection at the same time.  Strict order is
optional.


Requirements
------------

On Linux, you need `lksctp-tools` installed, at least with JDK 8.  If you see
an error about not being able to load a native library, that's the problem.
In other situations, you may need to make sure SCTP support is compiled into
your OS kernel.


Writing A Server
----------------

You need to code two things:

 * A `MessageType`, which simply defines a pair of bytes at the head of a
message to mark it as that flavor of message

```java
    static final MessageType WHAT_TIME_IS_IT = new MessageType("dateQuery", 1, 1);
    static final MessageType THE_TIME_IS = new MessageType("dateResponse", 1, 2);
```

 * A `MessageHandler` which can receive messages, and optionally reply to them
```java
    static class DateQueryHandler extends MessageHandler<DateRecord, Map> {

        DateQueryHandler() {
            super(Map.class);
        }

        @Override
        public Message<DateRecord> onMessage(Message<Map> data, ChannelHandlerContext ctx) {
            DateRecord response = new DateRecord();
            return RESPONSE.newMessage(response);
        }

        public static class DateRecord {
            public long when = System.currentTimeMillis();
        }
    }
```

The builder class `SctpServerAndClientBuilder` makes it simple to bind these and
create a server:

```java
    public static void main(String[] args) throws IOException, InterruptedException {
        Control<SctpServer> control = new SctpServerAndClientBuilder("date-demo")
                .onPort(8007)
                .withWorkerThreads(3)
                .bind(WHAT_TIME_IS_IT, DateQueryHandler.class)
                .bind(THE_TIME_IS, DateResponseHandler.class)
                .buildServer(args);
        SctpServer server = control.get();
        ChannelFuture future = server.start();
        future.sync();
    }
```

What this does:

 * Configure a server that understands our two MessageTypes, and passes handler
classes for both of them
 * Get back a `Control` object which can be used to shut down that server
 * Get the actual server instance
 * Start it, getting back a `ChannelFuture` which will complete when the 
connection is closed
 * Wait forever on that future, blocking the main thread


Writing a Client
----------------

`Sender` is a simple class which maintains a set of connections to clients;
you simply call it with a message and the address you want to send it to.
A client for the server above looks like:

```java
    public static void main(String[] args) throws IOException, InterruptedException {
        Control<Sender> control = new SctpServerAndClientBuilder("date-demo")
                .withHost("127.0.0.1")
                .onPort(8007)
                .bind(WHAT_TIME_IS_IT, DateDemo.DateQueryHandler.class)
                .bind(THE_TIME_IS, DateDemo.DateResponseHandler.class)
                .buildSender(args);

        Sender sender = control.get();

        for (int i = 0;; i++) {
            Address addr = new Address("127.0.0.1", 8007);
            // Just put some random stuff in the inbound message
            Map msg = new MapBuilder().put("id", i).put("client", true).build();
            Message<?> message = DateDemo.WHAT_TIME_IS_IT.newMessage(msg);
            sender.send(addr, message).addListener(new LogResultListener(i));
            Thread.sleep(5000);
        }
    }
```

What this does:

 * Configure a Sender with handlers for our message types
 * Loop forever sending a `WHAT_TIME_IS_IT` message every 5 seconds
 * You will see the response logged

See the subproject `scamper-date-demo` to build and run this.

Status
======

This library is fairly embryonic, but is usable at this point for experimenting
with SCTP.